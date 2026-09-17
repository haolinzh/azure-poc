# Azure MSI 接入 — 核心概念详解

> 本文是**概念解释**：讲清 MSI、Entra、client-id / object-id 是什么、为什么。偏"是什么 / 为什么"，不偏"怎么做"。
> 具体配置与脚本见 [`Azure-msi-guide.md`](./Azure-msi-guide.md)（各组件接入）和 [`Azure-msi-postgresql.md`](./Azure-msi-postgresql.md)（PG 专项）。

---

## 1. MSI / 托管标识（Managed Identity）是什么

**MSI = Managed Service Identity 的旧称**，现在统一叫 **Managed Identity（托管标识）**。

它是一种**身份**，存在 Entra 里，最大的特点是：**凭据（证书/密钥）由 Azure 自动管理、自动轮换，你永远不用碰密码**。

用它替代传统密码/连接串，好处是：

- 应用代码里**没有静态密码**，不存在泄露、过期、手动轮换的问题。
- 需要认证时，应用从 Azure 拿一个**短期 token**（几分钟到几小时有效），用完即弃。

### 两类托管标识

| 类型 | 生命周期 | 说明 |
|---|---|---|
| **System-assigned（系统分配）** | 跟着某个资源走 | 资源删了，标识也没了；一个资源只能有一个 |
| **User-assigned（用户分配，UAMI）** | 独立资源 | 可挂到多个资源上，生命周期独立 |

本项目用 **UAMI `hello-app-mi`**（User-Assigned Managed Identity）。

### 在 AKS 里怎么让 Pod 用上它

通过 **Workload Identity**：把 Pod 的 ServiceAccount **联邦**到 UAMI。这样 Pod 里就自动注入 `AZURE_CLIENT_ID / AZURE_TENANT_ID / AZURE_FEDERATED_TOKEN_FILE` 三个环境变量，应用用它们就能拿到 UAMI 的身份和 token——这就是"免密"的来源。

---

## 2. Microsoft Entra ID 是什么

**Entra ID** 是微软的**身份与访问管理服务（IAM）**，管三件事：

| 职责 | 说明 | 在本项目的体现 |
|---|---|---|
| **身份**（你是谁） | 登记用户、组、服务主体、托管标识 | `hello-app-mi` 就是它登记的一个身份 |
| **认证**（证明你是你） | 验证身份、签发 token | 应用连 PG 前先找它要 token |
| **授权**（你能干什么） | 角色（RBAC）、权限 | "给 `hello-app-mi` 授 Storage Blob Data Contributor" |

**改名说明**：它以前叫 **Azure Active Directory**（简称 **Azure AD / AAD**），2023 年改名为 Microsoft Entra ID。所以文档里出现的 **Entra、Entra ID、Azure AD、AAD 是同一个东西**的新旧叫法。每个 Azure 订阅都挂在一个 Entra tenant（租户/目录）下。

**类比**：把 Azure 想成"国家"，Entra 就是它的**户籍 + 身份证系统**——你和你的托管标识都在里面登记身份，办事（连服务）时先找它开"身份证明（token）"，办事窗口只认这个系统开的证明。

---

## 3. client-id vs object-id（最容易混的两个 ID）

托管标识在 Entra 里有**两条登记记录**，各有一个 ID，这就是两个 ID 的来源。

| ID | 类比 | 准确含义 | 什么时候用 |
|---|---|---|---|
| **client-id** | 这个人的**网名/账号** | Application (client) ID，标识"应用注册"记录 | 你想说"**用哪个身份**去登录/拿 token" |
| **object-id**（principalId） | 这个人的**身份证号** | Object ID / Principal ID，标识"服务主体"记录 | 你想说"**把哪个身份**登记进系统 / 给它开权限" |

同一个 UAMI 之所以有两个 ID，是因为 Entra 里「应用（application）」和「服务主体（service principal）」是两个东西；托管标识创建时，Azure 自动建了这两条。

### 记忆锚点

> 名字里带 **client**（客户端）的 → 客户端**拿来登录**的（点名）。
> 名字里带 **object / principal**（对象 / 主体）的 → 作为主体**被授权/匹配**的（登记）。

例子：`spring.cloud.azure.credential.client-id` = "我要用这个身份" → client-id；`pgaadauth_create_principal_with_oid` = "把这个主体登记进 PG" → object-id。

---

## 4. 各组件用哪个 ID（对照）

分三层看，规律清晰：

### ① Spring 配置/脚本里显式写出来的

| 组件 | 用哪个 ID | 出现位置 | 本项目值 |
|---|---|---|---|
| **全局凭据**（所有组件拿 token 都靠它） | **client-id** | `spring.cloud.azure.credential.client-id` | `7c7dbd38-…` |
| **Redis** | **object-id** | `spring.data.redis.username` | `fd30a409-…` |
| **PostgreSQL** | **object-id** | setup SQL `pgaadauth_create_principal_with_oid(…, '<object-id>', …)` | `fd30a409-…` |
| **App Insights** | **client-id** | env `APPLICATIONINSIGHTS_AUTHENTICATION_STRING` 里的 `ClientId=` | `7c7dbd38-…` |

### ② 不显式写 ID、隐式走全局 client-id 的

这几个组件配置里只有 endpoint/名称，认证统一靠全局 `credential.client-id` 点名身份换 token：

- **Event Hubs**（`namespace` + `event-hub-name`）
- **Storage Blob**（`account-name` + `endpoint`）
- **Key Vault**（`endpoint`）
- **Azure OpenAI**（`DefaultAzureCredential`，走全局 client-id）

### ③ Azure 控制面 RBAC 授权时（`az role assignment create`）

**所有组件的 `--assignee` 一律用 object-id**：Event Hubs Data Sender、Storage Blob Data Contributor、Key Vault Secrets User、Monitoring Metrics Publisher、Cognitive Services OpenAI User。

**一句话**：Spring 配置里只有 Redis 和 PG（脚本）显式用 object-id；其余要么走全局 client-id，要么不用写 ID。Azure 授权（RBAC）则全部用 object-id。

---

## 5. 认证流程总览（token 怎么流转）

```
1. Pod 里 workload identity 注入 AZURE_CLIENT_ID / AZURE_TENANT_ID / AZURE_FEDERATED_TOKEN_FILE
2. SDK 拿 client-id（点名）→ 找 Entra 做 token 交换（联邦凭据验证）
3. Entra 签发 token（JWT，token 里带身份信息，包括 object-id）
4. 应用拿 token 连目标服务（PG / Redis / Storage / …）
5. 服务端解析 token，用 object-id（匹配）查"这个身份被授了什么权"
6. 命中 → 放行，按授权执行
```

贯穿全文的一句话：**client-id 负责"我是谁、点名去登录"；object-id 负责"给谁开权限、登录后匹配成谁"。** 这两个 ID 是同一个托管标识在不同场景下的两张脸。
