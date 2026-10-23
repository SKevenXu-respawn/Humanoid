# ==============================
# 阶段一：构建阶段 (Builder)
# ==============================
# 使用带有 Maven 和 JDK 17 的镜像
FROM maven:3.9-eclipse-temurin-17 AS builder

# 设置工作目录
WORKDIR /build

# 先只复制 pom.xml，利用 Docker 缓存机制
# 这样如果你只改了 Java 代码，没改 pom.xml，就不会重新下载依赖，构建会快很多
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源代码
COPY src ./src

# 执行 Maven 打包，强烈建议加上 -DskipTests，因为在 Docker 构建时连不上数据库，测试必挂
RUN mvn clean package -DskipTests

# ==============================
# 阶段二：运行阶段 (Runner)
# ==============================
# 使用轻量级的 JRE 镜像
FROM eclipse-temurin:17-jre

WORKDIR /app

# 安装 Python 运行时（供 AI 的 shell 工具执行 python3 脚本）
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip ca-certificates \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && rm -rf /var/lib/apt/lists/*

# 从第一阶段（builder）的构建目录中，把打好的 jar 包复制过来
# 注意：请确认你的 target 目录下生成的 jar 包名称，如果不确定，可以用 *.jar 匹配
COPY --from=builder /build/target/*.jar app.jar

# 暴露 Spring Boot 的默认端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]