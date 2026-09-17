# Spring 项目接入 MSI 托管服务改动记录

> 目的：本地 Spring 项目部署到 AKS 后，通过 Managed Identity（Workload Identity + UAMI）免密连接托管 PostgreSQL / Redis / Event Hubs / Storage Blob / Key Vault / Azure OpenAI，并通过 Application Insights（Entra-only）上报遥测。密码、连接串、key 都不需要。
>
> Azure OpenAI（第 7 节）受订阅注册地监管限制：中国大陆个人订阅无法部署模型，代码已就绪、需合规订阅才能跑通。

## 0. 公共依赖与凭据

### `pom.xml` — BOM

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.azure.spring</groupId>
            <artifactId>spring-cloud-azure-dependencies</artifactId>
            <version>5.25.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### `application.properties` — 全局 MSI 凭据（所有服务共用）

```properties
spring.cloud.azure.credential.managed-identity-enabled=true
spring.cloud.azure.credential.client-id=<uami-client-id>
```

UAMI 有**两个 ID**，全文反复用到、最容易混淆，先集中讲清：

| 标识 | 占位符 | 用在哪 |
|---|---|---|
| **client-id**（客户端 ID / 应用 ID） | `<uami-client-id>` | 上面的全局凭据；App Insights 的 `ClientId=`（第 6 节）；OpenAI 的 `DefaultAzureCredential`（第 7 节） |
| **object-id**（对象 ID / 主体 ID / principalId） | `<uami-object-id>` | Redis 的 username（第 2 节）；PG 建角色（第 1 节）；RBAC 授权 `--assignee` |

两者在同一处获取：Azure 门户 → 托管标识 `hello-app-mi` → 概览 → **客户端 ID** / **对象(主体) ID**；或
`az identity show -g <rg> -n hello-app-mi --query clientId -o tsv` / `--query principalId -o tsv`。

---

## 1. PostgreSQL

### 依赖

```xml
<dependency>
    <groupId>com.azure.spring</groupId>
    <artifactId>spring-cloud-azure-starter-jdbc-postgresql</artifactId>
</dependency>
```

### 配置

```properties
spring.datasource.url=jdbc:postgresql://<pg-hostname>:5432/<db-name>?sslmode=require
spring.datasource.username=<db-role>
spring.datasource.azure.passwordless-enabled=true
```

- `<pg-hostname>`：PostgreSQL Flexible Server 的主机名（FQDN）。
  - 获取：Azure 门户 → PostgreSQL 服务器 → 概览 → **服务器名称**；或 `az postgres flexible-server show -g <rg> -n pg-hello-poc --query fullyQualifiedDomainName -o tsv`。
- `<db-name>`：要连接的数据库名。
  - 获取：该 PG 服务器里已创建的数据库名（如 `demo`）；门户 → 服务器 → **数据库** 边栏；或 psql 执行 `\l` 查看。
- `<db-role>`：映射到 UAMI 的数据库角色名。
  - 获取：setup SQL 里 `pgaadauth_create_principal_with_oid('<db-role>', '<uami-object-id>', 'service', ...)` 的第一个参数（自己起的名字，如 `hello-app-mi`）。

JdbcTemplate 的 DAO 代码无需改动。

---

## 2. Redis（Azure Managed Redis）

### 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>com.azure.spring</groupId>
    <artifactId>spring-cloud-azure-starter-data-redis-lettuce</artifactId>
</dependency>
```

### 配置

```properties
spring.data.redis.host=<redis-hostname>
spring.data.redis.port=10000
spring.data.redis.ssl.enabled=true
spring.data.redis.username=<uami-object-id>
spring.data.redis.azure.passwordless-enabled=true
```

- `<redis-hostname>`：Azure Managed Redis 的主机名（形如 `<name>.<region>.redis.azure.net`）。
  - 获取：Azure 门户 → Azure Managed Redis → 概览 → **主机名**；或 `az redisenterprise show -g <rg> -n redis-hello-poc --query hostName -o tsv`。
- `<uami-object-id>`：UAMI 的 **object-id**（见第 0 节，注意不是 client-id）。
- 端口固定 `10000`（Azure Managed Redis；老的 Azure Cache for Redis 才是 `6380`）。

代码注入 `StringRedisTemplate` 即可。

---

## 3. Event Hubs

### 依赖

```xml
<dependency>
    <groupId>com.azure.spring</groupId>
    <artifactId>spring-cloud-azure-starter-integration-eventhubs</artifactId>
</dependency>
```

### 配置

```properties
spring.cloud.azure.eventhubs.namespace=<namespace>
spring.cloud.azure.eventhubs.event-hub-name=<hub-name>
```

- `<namespace>`：Event Hubs 命名空间名。
  - 获取：Azure 门户 → Event Hubs 命名空间 → 概览 → **名称**；或 `az eventhubs namespace show -g <rg> -n eh-hello-poc --query name -o tsv`。
- `<hub-name>`：命名空间里的事件中心名。
  - 获取：Azure 门户 → 命名空间 → **实体 → 事件中心** 列表里的名字（如 `hello-hub`）。

### 发送代码

注入 `EventHubsTemplate`（`com.azure.spring.messaging.eventhubs.core.EventHubsTemplate`）：

```java
template.send("<hub-name>", MessageBuilder.withPayload(payload).build());
```

> 前提：UAMI 在 Event Hubs 命名空间上被授予「Azure Event Hubs Data Sender」角色。

---

## 4. Storage Blob

### 依赖

```xml
<dependency>
    <groupId>com.azure.spring</groupId>
    <artifactId>spring-cloud-azure-starter-storage-blob</artifactId>
</dependency>
```

### 配置

```properties
spring.cloud.azure.storage.blob.account-name=<account-name>
spring.cloud.azure.storage.blob.endpoint=https://<account-name>.blob.core.windows.net
```

- `<account-name>`：存储账户名。
  - 获取：Azure 门户 → 存储账户 → 概览 → **名称**；或 `az storage account show -g <rg> -n sthellopoc --query name -o tsv`。

### 代码

注入 `BlobServiceClient`（`com.azure.storage.blob.BlobServiceClient`），由 starter 自动配置：

```java
BlobClient blob = blobService.getBlobContainerClient("<container>").getBlobClient("<blob-name>");
blob.upload(BinaryData.fromString("<content>"), true);   // 上传（覆盖）
blobService.getBlobContainerClient("<container>").listBlobs();  // 列举
```

- `<container>`：Blob 容器名（如 `demo`）。
- `<blob-name>`：Blob 文件名。
- `<content>`：要写入的字符串内容。

> 前提：UAMI 在存储账户上被授予「Storage Blob Data Contributor」角色。

---

## 5. Key Vault

### 依赖

```xml
<dependency>
    <groupId>com.azure.spring</groupId>
    <artifactId>spring-cloud-azure-starter-keyvault-secrets</artifactId>
</dependency>
```

### 配置

```properties
spring.cloud.azure.keyvault.secret.endpoint=https://<kv-name>.vault.azure.net/
```

- `<kv-name>`：Key Vault 名称。
  - 获取：Azure 门户 → Key Vault → 概览 → **Vault URI**（去掉 `https://` 前缀和末尾 `/`）；或 `az keyvault show -g <rg> -n kv-hello-poc --query properties.vaultUri -o tsv`。

### 代码

注入 `SecretClient`（`com.azure.security.keyvault.secrets.SecretClient`），由 starter 自动配置：

```java
String value = secretClient.getSecret("<secret-name>").getValue();
```

- `<secret-name>`：Key Vault 里已创建的密钥名。

> 前提：UAMI 在 Key Vault 上被授予「Key Vault Secrets User」（只读）或「Key Vault Secrets Officer」（读写）角色。

---

## 6. Log Analytics Workspace + Application Insights（Entra-only）

### 说明

- App Insights 采用 **workspace-based**（数据落到 LAW），并关闭本地认证（`DisableLocalAuth=true`），即 **Entra-only**：连接串里的 `InstrumentationKey` 只用于标识资源，真正的摄取凭据走 AAD。
- LAW（Log Analytics Workspace）只是 App Insights 的数据落点，**无需任何 Spring 侧配置**——只要 App Insights 是 workspace-based 并关联到它即可。
- **关键**：不能用 `spring-cloud-azure-starter-monitor`（OTel distro 不支持 AAD 摄取），必须用 **Application Insights Java agent**（standalone jar）。Agent 自动采集请求/依赖/日志/指标，无需写任何 Java 代码。

### Dockerfile（下载并挂载 agent）

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ADD https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/3.7.9/applicationinsights-agent-3.7.9.jar /app/applicationinsights-agent.jar
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/applicationinsights-agent.jar", "-jar", "app.jar"]
```

### 环境变量（K8s Deployment 注入）

```yaml
env:
  - name: APPLICATIONINSIGHTS_CONNECTION_STRING
    value: "InstrumentationKey=<instrumentation-key>;IngestionEndpoint=<ingestion-endpoint>;LiveEndpoint=<live-endpoint>;ApplicationId=<app-id>"
  - name: APPLICATIONINSIGHTS_AUTHENTICATION_STRING
    value: "Authorization=AAD;ClientId=<uami-client-id>"
```

- `<instrumentation-key>` / `<ingestion-endpoint>` / `<live-endpoint>` / `<app-id>`：整条**连接字符串**。
  - 获取：Azure 门户 → Application Insights 资源 → 概览 → **连接字符串**（直接整段复制，`;` 分隔的四个字段就是这些占位符）。
- `<uami-client-id>`：UAMI 的 **client-id**（见第 0 节）。

> 前提：UAMI 在 App Insights 资源上被授予「Monitoring Metrics Publisher」角色；LAW 数据平面授权由该 workspace-based 关联自动处理。

---

## 7. Azure OpenAI

> ⚠️ **监管限制**：部署模型时若订阅注册地为**中国大陆**，会报 `CannotDeployDueToLocalRegulations`——仅企业客户（营业执照）经微软认证合作伙伴可订阅 Azure OpenAI，个人订阅无法部署模型。本节代码已就绪并可编译，但需**合规订阅**才能端到端验证。

### 依赖（原生 SDK，非 spring-cloud-azure starter）

```xml
<dependency>
    <groupId>com.azure</groupId>
    <artifactId>azure-ai-openai</artifactId>
    <version>1.0.0-beta.16</version>
</dependency>
```

### 配置

```properties
spring.cloud.azure.openai.endpoint=https://<resource-name>.openai.azure.com
spring.cloud.azure.openai.deployment-name=<deployment-name>
```

- `<resource-name>`：Azure OpenAI 资源名（endpoint 的 FQDN 是 `<resource-name>.openai.azure.com`）。
  - 获取：Azure 门户 → Azure OpenAI 资源 → 概览 → **终结点**（去掉 `https://` 前缀）；或 `az cognitiveservices account show -g <rg> -n openai-hello-poc --query properties.endpoint -o tsv`。
- `<deployment-name>`：资源里已部署的**模型部署名**（不是模型名本身，是你部署时起的名字）。
  - 获取：Azure 门户 → Azure OpenAI 资源 → **模型部署** 边栏 → 部署名称；或 `az cognitiveservices account deployment list -g <rg> -n openai-hello-poc --query '[].name' -o tsv`。

### 代码

手动用 `DefaultAzureCredential` 构造 `OpenAIClient`（认证链和前面组件一致：workload identity → token exchange → Entra token）：

```java
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;

@Bean
public OpenAIClient openAIClient(@Value("${spring.cloud.azure.openai.endpoint}") String endpoint) {
    return new OpenAIClientBuilder()
            .endpoint(endpoint)
            .credential(new DefaultAzureCredentialBuilder().build())
            .buildClient();
}
```

调用：

```java
ChatCompletions r = client.getChatCompletions(
    deployment,
    new ChatCompletionsOptions(List.of(new ChatRequestUserMessage(prompt))));
String reply = r.getChoices().get(0).getMessage().getContent();
```

> 前提：UAMI 在 Azure OpenAI 资源上被授予「Cognitive Services OpenAI User」角色；资源需部署至少一个模型。

---

## 8. 连通性验证

前置：应用已部署到 AKS 且通过 LoadBalancer 暴露；`<public-ip>` = Service 的 EXTERNAL-IP。
获取：`kubectl get svc hello-app -o jsonpath='{.status.loadBalancer.ingress[0].ip}'`。

| 组件 | 验证命令 | 预期 |
|---|---|---|
| Hello（基线） | `curl <public-ip>/hello` | `{"message":"Hello from AKS!"}` |
| PostgreSQL 写入 | `curl -X POST <public-ip>/messages -H 'Content-Type: application/json' -d '{"content":"ping"}'` | 返回带 `id` 的记录 |
| PostgreSQL 读回 | `curl <public-ip>/messages` | 列表含上面那条 |
| Redis 写入 | `curl -X POST <public-ip>/redis -H 'Content-Type: application/json' -d '{"key":"k","value":"v"}'` | `{"key":"k","value":"v"}` |
| Redis 读回 | `curl <public-ip>/redis/k` | `{"key":"k","value":"v"}` |
| Event Hubs 发送 | `curl -X POST <public-ip>/eventhub/send -H 'Content-Type: application/json' -d '{"content":"ping"}'` | `{"status":"sent",...}` |
| Storage 上传 | `curl -X POST <public-ip>/storage/upload -H 'Content-Type: application/json' -d '{"name":"a.txt","content":"hi"}'` | `{"blob":"a.txt",...}` |
| Storage 列举 | `curl <public-ip>/storage/list` | 含 `a.txt` 的列表 |
| Key Vault 读取 | `curl <public-ip>/keyvault/secret/<secret-name>` | `{"name":"...","value":"..."}` |
| Azure OpenAI | `curl -X POST <public-ip>/chat -H 'Content-Type: application/json' -d '{"prompt":"hi"}'` | 需模型已部署，返回 `{"reply":"..."}` |

`<secret-name>`：第 5 节里已创建的 secret 名。

App Insights / LAW 不是 HTTP 端点，验证方式：触发上表任意请求后，查询 LAW 的 `AppRequests` 表能看到对应记录：

```bash
az monitor log-analytics query -w <law-workspace-id> \
  --analytics-query "AppRequests | where TimeGenerated > ago(15m) | summarize count() by Name"
```

- `<law-workspace-id>`：LAW 的 **Workspace ID（Customer ID）**。
  - 获取：Azure 门户 → Log Analytics 工作区 → 概览 → **工作区 ID**；或 `az monitor log-analytics workspace list --query '[].customerId' -o tsv`。

### 最近一次验证结果（2026-09-17）

Hello / PostgreSQL / Redis / Event Hubs / Storage Blob / Key Vault 全部 HTTP 200，且 App Insights 遥测正常流入 LAW。Azure OpenAI `/chat` 返回 404——代码在仓库已就绪，但模型部署被订阅监管限制挡住、尚未上线。
