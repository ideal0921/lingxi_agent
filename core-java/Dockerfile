# 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY start/pom.xml start/pom.xml
RUN mvn -q -B dependency:go-offline
COPY . .
RUN mvn -q -B -pl start -am package -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/start/target/lingxi-start-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
