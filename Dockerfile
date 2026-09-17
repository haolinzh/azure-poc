FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ADD https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/3.7.9/applicationinsights-agent-3.7.9.jar /app/applicationinsights-agent.jar
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/applicationinsights-agent.jar", "-jar", "app.jar"]
