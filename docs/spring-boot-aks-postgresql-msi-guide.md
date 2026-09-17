# Spring 项目接入 MSI 托管服务改动记录

> 目的：本地 Spring 项目部署到 AKS 后，通过 Managed Identity（Workload Identity + UAMI）免密连接托管 PostgreSQL / Redis / Event Hubs / Storage Blob / Key Vault，并通过 Application Insights（Entra-only）上报遥测。密码、连接串、key 都不需要。

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

### `application.properties` — 全局 MSI 凭据（三个服务共用）

```properties
spring.cloud.azure.credential.managed-identity-enabled=true
spring.cloud.azure.credential.client-id=<uami-client-id>
```

- `<uami-client-id>`：UAMI 的 **client-id（应用/客户端 ID）**。
  - 获取：Azure 门户 → 托管标识 `hello-app-mi` → 概览 → **客户端 ID**；或 `az identity show -g <rg> -n hello-app-mi --query clientId -o tsv`。

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
- `<uami-object-id>`：UAMI 的 **objectId（主体/对象 ID）**，注意**不是** client-id，也不是资源名。
  - 获取：Azure 门户 → 托管标识 `hello-app-mi` → 概览 → **对象(主体) ID**；或 `az identity show -g <rg> -n hello-app-mi --query principalId -o tsv`。
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
- `<uami-client-id>`：UAMI 的 **client-id**（同第 0 节全局凭据里的那个）。
  - 获取：Azure 门户 → 托管标识 `hello-app-mi` → 概览 → **客户端 ID**；或 `az identity show -g <rg> -n hello-app-mi --query clientId -o tsv`。

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
