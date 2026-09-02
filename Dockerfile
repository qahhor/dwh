# Multi-stage образ backend-приложения SmartupCMS:
#   docker build -t smartupcms/server:<ver> .
#
# Слоистая сборка (Spring Boot layertools): зависимости кэшируются отдельно от
# кода приложения — пересборка после правки кода не тянет заново ~100 МБ библиотек.

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753 AS build
WORKDIR /build

COPY pom.xml .
COPY libs libs
COPY apps/server apps/server

# Тесты в образе не гоняем: это делает CI (там Docker для Testcontainers).
# Cache mount для ~/.m2: зависимости скачиваются один раз и переиспользуются
# между сборками. Без него каждая сборка тянет ~100 МБ заново — первая сборка
# занимала минуты и упиралась в таймауты.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl apps/server -am -DskipTests package \
 && cp apps/server/target/server-*.jar /build/app.jar

# Распаковка fat-jar: рядом появляются lib/ (зависимости) и запускаемый jar.
# Разделение нужно для кэша Docker: lib меняется редко, код — каждую сборку.
WORKDIR /layers
RUN java -Djarmode=tools -jar /build/app.jar extract --destination /layers \
 && mv /layers/app-*.jar /layers/run.jar 2>/dev/null || mv /layers/*.jar /layers/run.jar

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112 AS runtime

# Hardening:non-root пользователь, только необходимые пакеты, чистый apt-кэш
RUN apt-get update && apt-get upgrade -y --no-install-recommends \
 && groupadd --system --gid 10001 dwh \
 && useradd  --system --uid 10001 --gid dwh --home-dir /app --shell /usr/sbin/nologin dwh \
 && apt-get install -y --no-install-recommends curl \
 && rm -f /usr/bin/pebble \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Каталог local_disk provider под non-root. В production этот путь обязан быть
# томом; S3-compatible provider хранит bytes вне контейнера.
RUN mkdir -p /var/lib/smartupcms/storage /var/lib/smartupcms/backup /opt/smartupcms/jna \
 && chown -R dwh:dwh /var/lib/smartupcms /opt/smartupcms/jna
ENV DWH_STORAGE_LOCAL_PATH=/var/lib/smartupcms/storage \
    DWH_BACKUP_STATUS_FILE=/var/lib/smartupcms/backup/status.json
VOLUME ["/var/lib/smartupcms"]

# Порядок COPY = порядок изменчивости (реже меняется — раньше): зависимости,
# затем код приложения. Правка кода не инвалидирует ~100 МБ слоя с библиотеками.
COPY --from=build --chown=dwh:dwh /layers/lib     ./lib
COPY --from=build --chown=dwh:dwh /layers/run.jar ./app.jar

USER dwh:dwh
EXPOSE 8080 9090

# Контейнерные умолчания JVM: heap от лимита памяти cgroup, не от хоста.
# JAVA_TOOL_OPTIONS вместо своей переменной — JVM подхватывает её сама,
# поэтому ENTRYPOINT остаётся exec-формой без шелла (см. ниже).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 \
-XX:+ExitOnOutOfMemoryError -XX:+UseZGC \
-Djava.security.egd=file:/dev/./urandom -Duser.timezone=UTC \
-Djna.tmpdir=/opt/smartupcms/jna"


# Health-check уровня контейнера; Compose использует тот же readiness endpoint.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=4 \
  CMD curl -fsS http://127.0.0.1:${MANAGEMENT_PORT:-9090}/actuator/health/readiness || exit 1

# Exec-форма обязательна: при ENTRYPOINT ["sh","-c","..."] аргументы из
# command (например --spring.profiles.active=migrate) НЕ доходят до Java —
# из-за этого шаг миграций молча запускался с профилем приложения.
ENTRYPOINT ["java", "-jar", "app.jar"]
