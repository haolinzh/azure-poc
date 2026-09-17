# PostgreSQL 接入 MSI 托管身份 — 完整说明

> 目的：Spring 应用部署到 AKS 后，通过托管标识（UAMI）免密连接托管 PostgreSQL Flexible Server。本文聚焦 PG 这一环，讲清三件事：**为什么必须做一次数据库侧初始化、脚本是什么、每一步在干什么**。
>
> 其余组件（Redis / Event Hubs / Storage Blob / Key Vault / App Insights / OpenAI）的接入见 [`Azure-msi-guide.md`](./Azure-msi-guide.md)。

---

## 1. 为什么必须做这一步（核心）

MSI 免密和密码登录的本质区别：

- **密码登录**：数据库自己存着密码，连接时"对密码"。
- **MSI 免密**：应用拿 UAMI 换来的 Entra token 当凭据，数据库要"信任 token"。

要让数据库信任 token，它必须先知道一件事：**哪个 Azure 身份 = 数据库里哪个 role、有哪些权限**。这条映射必须建立一次，否则 PG 拿到 token 也不知道该放行成谁。这就是"为什么一定要这样做"的根源——它不是 Spring 代码的依赖，是数据库侧的固有初始化。

### 认证时怎么串起来

```
Spring 应用（UAMI）
   │  1. workload identity 拿 Entra token（token 里带 UAMI 的 object-id）
   ▼
PG Flexible Server
   │  2. 解析 token 里的 object-id
   │  3. 查"object-id ↔ role"映射表（就是初始化时登记的那条）
   │  4. 命中 → 当你是那个 role，放行 + 按 role 的权限执行
   ▼
demo 库 / messages 表
```

关键：**PG 是用 object-id 匹配 token 和 role 的，不是用用户名**。所以初始化时登记的 object-id 必须和 token 里的 object-id 一致。

### 为什么只有 PG 需要"登录进数据库跑 SQL"

| 组件 | 身份→权限的映射存在哪 | 需要登录服务跑 SQL 吗 |
|---|---|---|
| **PostgreSQL** | 数据库内部（PG role ↔ object-id） | ✅ 唯一需要 |
| Redis / Event Hubs / Storage / Key Vault / App Insights / OpenAI | Azure 控制面（RBAC 角色分配） | ❌ 不碰数据面 |

原因：Redis、Storage、Key Vault 这些把授权信息放在 **Azure 控制面**（`az role assignment create` 那种 RBAC），由 Azure 平台完成 token→权限的匹配，你不用登录进服务。而 PG 把"身份→role"的映射存在了**数据库内部**，所以必须登录进去建一次。

---

## 2. 依赖与配置（Spring 侧）

### 依赖 `pom.xml`

```xml
<dependency>
    <groupId>com.azure.spring</groupId>
    <artifactId>spring-cloud-azure-starter-jdbc-postgresql</artifactId>
</dependency>
```

（BOM `spring-cloud-azure-dependencies:5.25.0` 见总指南第 0 节）

### 配置 `application.properties`

```properties
spring.datasource.url=jdbc:postgresql://<pg-hostname>:5432/<db-name>?sslmode=require
spring.datasource.username=<db-role>
spring.datasource.azure.passwordless-enabled=true
spring.cloud.azure.credential.managed-identity-enabled=true
spring.cloud.azure.credential.client-id=<uami-client-id>
```

占位符说明（含本项目参考值）：

- `<pg-hostname>`：PG Flexible Server 主机名（FQDN）。参考值 `pg-hello-poc.postgres.database.azure.com`
- `<db-name>`：要连接的数据库名。参考值 `demo`
- `<db-role>`：映射到 UAMI 的数据库角色名（就是下面脚本里建的那个 role）。参考值 `hello-app-mi`
- `<uami-client-id>`：UAMI 的 client-id。参考值 `7c7dbd38-b3a0-49c6-a55a-27a6237645cf`

`JdbcTemplate` 的 DAO 代码无需改动。

---

## 3. 一次性初始化脚本（setup SQL）

托管 PG 首次上线时，登录进数据库跑一次（管理员账号连默认 `postgres` 库），把「库 + 角色 + 表 + 权限」一次建好。

```sql
-- 1) 建应用要用的库
CREATE DATABASE <db-name>;

-- 2) 建角色并绑定到托管标识 UAMI（免密登录的核心）
--    第二个参数是 UAMI 的 object-id，不是 client-id
SELECT * FROM pgaadauth_create_principal_with_oid('<db-role>', '<uami-object-id>', 'service', false, false);

-- 3) 允许该角色连库
GRANT CONNECT ON DATABASE <db-name> TO "<db-role>";

-- 4) 切到目标库，下面都在库里做
\c <db-name>

-- 5) 建表（IF NOT EXISTS 防止重复跑报错）
CREATE TABLE IF NOT EXISTS messages (
  id BIGSERIAL PRIMARY KEY,
  content TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 6) 授权
GRANT USAGE ON SCHEMA public TO "<db-role>";
GRANT SELECT, INSERT, UPDATE, DELETE ON messages TO "<db-role>";
GRANT USAGE, SELECT ON SEQUENCE messages_id_seq TO "<db-role>";
```

参考值（本项目）：

| 占位符 | 值 |
|---|---|
| `<db-name>` | `demo` |
| `<db-role>` | `hello-app-mi` |
| `<uami-object-id>` | `fd30a409-df43-426c-8593-58e4cbae0eec` |

入口：`\c` 是 psql 专有命令。用集群内 `psql`（PG 无公网端点）整段一次跑；若用门户「查询编辑器」或 JDBC 客户端（不认 `\c`），拆两次连接——先连 `postgres` 跑 1–3 句，再连 `<db-name>` 跑 5–6 句。

### 从空库到可用的四步

脚本其实就是把四样东西从零造出来：

1. **造库**（`demo`）——空库里没有，先建；`application.properties` 里的 URL 指的就是它。
2. **造角色 + 绑定 UAMI**——空库里没有 `hello-app-mi`，更没有"UAMI→role"映射；`pgaadauth_create_principal_with_oid` 这条就是干这个。
3. **造表**（`messages`）——应用要读写的那张表。
4. **授权**——角色默认啥权限都没有，逐条给它开（连库 / 用 schema / 读写表 / 用自增序列）。

跑完一次，托管 PG 就从"刚上线"变成"应用可免密连"的状态。

---

## 4. `pgaadauth_create_principal_with_oid` 函数详解

属于 **PGAadAuth 扩展**（服务器开启 Microsoft Entra 认证时自动启用）。完整签名：

```sql
pgaadauth_create_principal_with_oid(
    role_name      text,     -- 你要创建的 PG 角色名
    object_id      text,     -- Entra 对象的 object ID（身份唯一标识）
    principal_type text,     -- 'user' | 'group' | 'service'
    is_admin       boolean,  -- 是否管理员
    is_mfa         boolean   -- 是否要求 MFA
)
```

> 注：第 4、5 个 boolean 参数在不同文档版本的命名有出入（有的写 `is_admin`/`is_mfa`，有的写 `is_mfa`/`is_enabled`），但行为一致——**对 managed identity 一律传 `false, false`**，含义是"非管理员 + 不要求 MFA"，与"登录开关"无关。

### 参数逐个讲

**1. `role_name` — 角色名**

要创建的 PG 角色名，你自定义。之后 `spring.datasource.username` 就填这个。本项目 = `hello-app-mi`。

**2. `object_id` — Entra 对象的 object ID（最重要）**

绑定目标身份的唯一标识。认证匹配就是靠它——PG 用 object-id 匹配 token 和 role。对 UAMI 填它的 **principalId（object-id）**，不是 client-id。本项目 = `fd30a409-df43-426c-8593-58e4cbae0eec`。

**3. `principal_type` — 身份类型**

| 值 | 含义 |
|---|---|
| `'user'` | Entra 用户（真人） |
| `'group'` | Entra 组 |
| `'service'` | 服务主体 / 托管标识 |

UAMI 本质是 service principal，所以填 `'service'`。

**4. `is_admin` — 是否管理员**

`false`。普通应用身份不该是管理员。

**5. `is_mfa` — 是否要求 MFA**

`false`。托管标识没有"交互式 MFA"这回事，必须 `false`。

### 为什么是 `SELECT` 语句却能建角色

PostgreSQL 里函数可以有副作用，`SELECT 函数()` 只是"调用函数"的标准写法。真正建角色、记映射是函数内部执行的 DDL，`SELECT *` 只是把函数返回的那一行打印出来。类比 `SELECT nextval('seq')` 会推进序列、`SELECT pg_reload_conf()` 会重载配置。

### 相关函数

- `pgaadauth_create_principal(...)`：同上，但用 email/UPN 而非 object id（给真人用户用）。
- `pgaadauth_list_principals()`：列出已建的所有 Entra 映射（可用来核对）。
- `pgaadauth_drop_principal(...)`：删除某个映射。

---

## 5. 常见坑 / 注意点

1. **object-id ≠ client-id**：`pgaadauth_create_principal_with_oid` 第二个参数、Redis username 用的都是 object-id（`fd30a409-...`）；Spring 全局凭据、App Insights `ClientId=` 用的是 client-id（`7c7dbd38-...`）。两者别混。
2. **删 Entra 身份 ≠ 删 role**：在 Entra 里删了 UAMI，PG 里的 `hello-app-mi` role 还在，但拿不到新 token，等于失效——需手动 `DROP ROLE`。
3. **同名不同人连不上**：PG 认 object-id 不认名字。删一个 Entra 用户再建同名用户，新用户连不上旧 role（object-id 变了）。
4. **`pgaadauth_*` 函数只存在于 `postgres` 库**：在别的库里跑会报 `function does not exist`。
5. **`false, false` 不是"禁用登录"**：它表示"非管理员 + 不要求 MFA"，建完即可登录，无需后续 `ALTER ROLE` 启用。
