# ====== 构建阶段 ======
# 多模块 Maven 构建：context 必须为项目根目录，以访问父 pom 和 quant-common
FROM maven:3.9-eclipse-temurin-17 AS builder
ARG MODULE
WORKDIR /build

# 先拷贝所有 pom.xml（利用 Docker 层缓存：pom 不变时跳过依赖下载）
COPY pom.xml ./
COPY quant-common/pom.xml quant-common/
COPY quant-data-service/pom.xml quant-data-service/
COPY quant-core-service/pom.xml quant-core-service/
COPY quant-gateway/pom.xml quant-gateway/

# 配置 Maven 阿里云镜像（国内加速依赖下载，Maven Central 可能 403）
COPY docker/maven-settings.xml /root/.m2/settings.xml

# 预下载依赖（-pl 指定目标模块，-am 同时解析依赖模块）
# 允许失败：首次构建或 pom 变更时下载依赖，后续命中缓存
RUN mvn -pl ${MODULE} -am -q dependency:go-offline -DskipTests || true

# 拷贝所有模块源码（-am 构建依赖模块时需要其源码）
COPY quant-common/src quant-common/src
COPY quant-data-service/src quant-data-service/src
COPY quant-core-service/src quant-core-service/src
COPY quant-gateway/src quant-gateway/src

# 构建目标模块及其依赖模块，跳过测试
RUN mvn -pl ${MODULE} -am -q clean package -DskipTests

# ====== 运行阶段 ======
FROM eclipse-temurin:17-jre
ARG MODULE
WORKDIR /app

# 拷贝构建产物
COPY --from=builder /build/${MODULE}/target/*.jar app.jar

# healthcheck 脚本：Actuator HTTP 级探测（bash /dev/tcp，无需 curl/wget）
COPY docker/healthcheck.sh /healthcheck.sh
RUN chmod +x /healthcheck.sh

ENTRYPOINT ["java", "-jar", "app.jar"]
