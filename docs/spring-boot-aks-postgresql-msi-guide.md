# Spring 项目接入 MSI PostgreSQL 改动记录

> 本次改动目的：让本地 Spring 项目部署到 AKS 后，通过 Managed Identity 免密连接托管 PostgreSQL（PGSQL）。

## 1. `pom.xml`

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

<dependencies>
    <dependency>
        <groupId>com.azure.spring</groupId>
        <artifactId>spring-cloud-azure-starter-jdbc-postgresql</artifactId>
    </dependency>
</dependencies>
```

## 2. `application.properties`

```properties
spring.datasource.url=jdbc:postgresql://<pg-hostname>:5432/<db-name>?sslmode=require
spring.datasource.username=<db-role>
spring.datasource.azure.passwordless-enabled=true
spring.cloud.azure.credential.managed-identity-enabled=true
spring.cloud.azure.credential.client-id=<uami-client-id>
```

- `<pg-hostname>`：PostgreSQL 服务器主机名
- `<db-name>`：数据库名
- `<db-role>`：映射到 UAMI 的 DB 角色名
- `<uami-client-id>`：用户分配托管标识的 client-id

JdbcTemplate 的 DAO 代码无需改动。
