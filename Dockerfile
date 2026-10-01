# Multi-stage образ backend-приложения SmartupCMS:
#   docker build -t smartupcms/server:<ver> .
#
# Слоистая сборка (Spring Boot layertools): зависимости кэшируются отдельно от
# кода приложения — пересборка после правки кода не тянет заново ~100 МБ библиотек.

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
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
FROM eclipse-temurin:25-jre@sha256:8da0490fa9a3c26867012019565948eef0ee69438f5c75ac28146967bae984b5 AS runtime

# Hardening:non-root пользователь, только необходимые пакеты, чистый apt-кэш
RUN apt-get update && apt-get upgrade -y --no-install-recommends \
 && groupadd --system --gid 10001 smartupcms \
 && useradd  --system --uid 10001 --gid smartupcms --home-dir /app --shell /usr/sbin/nologin smartupcms \
 && apt-get install -y --no-install-recommends curl \
 && rm -f /usr/bin/pebble \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Каталог local_disk provider под non-root. В production этот путь обязан быть
# томом; S3-compatible provider хранит bytes вне контейнера.
RUN mkdir -p /var/lib/smartupcms/storage /var/lib/smartupcms/backup /var/lib/smartupcms/logs /var/lib/smartupcms/audit-archive /opt/smartupcms/jna \
 && chown -R smartupcms:smartupcms /var/lib/smartupcms /opt/smartupcms/jna
ENV SMC_STORAGE_LOCAL_PATH=/var/lib/smartupcms/storage \
    SMC_BACKUP_STATUS_FILE=/var/lib/smartupcms/backup/status.json
VOLUME ["/var/lib/smartupcms"]

# Порядок COPY = порядок изменчивости (реже меняется — раньше): зависимости,
# затем код приложения. Правка кода не инвалидирует ~100 МБ слоя с библиотеками.
COPY --from=build --chown=smartupcms:smartupcms /layers/lib     ./lib
COPY --from=build --chown=smartupcms:smartupcms /layers/run.jar ./app.jar

USER smartupcms:smartupcms
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
