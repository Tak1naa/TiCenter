# AI 智能题库 - 后端镜像（Java 25 + Spring Boot）
# 构建所有服务的 jar，运行时用 command 指定启动哪个
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY backend/pom.xml .
COPY backend/common/pom.xml backend/common/
COPY backend/document-service/pom.xml backend/document-service/
COPY backend/vector-service/pom.xml backend/vector-service/
COPY backend/chat-service/pom.xml backend/chat-service/
COPY backend/core-app/pom.xml backend/core-app/
RUN mvn -q -pl common -am dependency:go-offline 2>/dev/null || true
COPY backend/ .
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/chat-service/target/chat-service-*-exec.jar /app/chat-service.jar
COPY --from=build /build/document-service/target/document-service-*-exec.jar /app/document-service.jar
COPY --from=build /build/vector-service/target/vector-service-*-exec.jar /app/vector-service.jar
COPY --from=build /build/core-app/target/core-app-*.jar /app/core-app.jar
EXPOSE 39181 39182 39183
ENTRYPOINT ["java", "-jar", "/app/core-app.jar"]
