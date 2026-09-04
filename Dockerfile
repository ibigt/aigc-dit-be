# dit-server 多阶段构建：Maven 打包 → JRE + ffmpeg（抽封面）
# 日常更新若已在本机 Maven 打包，可用 deploy/Dockerfile.prebuilt 只替换 /app/app.jar，见 deploy/update-backend-image.sh
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

COPY deploy/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY dit-common/pom.xml dit-common/pom.xml
COPY dit-storage/pom.xml dit-storage/pom.xml
COPY dit-generate/pom.xml dit-generate/pom.xml
COPY dit-server/pom.xml dit-server/pom.xml

RUN mvn -B -pl dit-server -am dependency:go-offline -DskipTests || true

COPY dit-common dit-common
COPY dit-storage dit-storage
COPY dit-generate dit-generate
COPY dit-server dit-server

RUN mvn -B -pl dit-server -am package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /mnt/ai-gc/logs

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m"

COPY --from=builder /build/dit-server/target/dit-server-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
    CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
