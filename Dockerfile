# Multi-stage образ приложений DWH. Один Dockerfile на оба приложения:
#   docker build --build-arg APP=instance      -t dwh/instance:<ver> .
#   docker build --build-arg APP=control-plane -t dwh/control-plane:<ver> .
#
# Слоистая сборка (Spring Boot layertools): зависимости кэшируются отдельно от
# кода приложения — пересборка после правки кода не тянет заново ~100 МБ библиотек.

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Сначала только POM'ы — слой с зависимостями переиспользуется, пока они не менялись
COPY pom.xml .
COPY libs/core-types/pom.xml   libs/core-types/
COPY libs/provider-spi/pom.xml libs/provider-spi/
COPY apps/instance/pom.xml     apps/instance/
COPY apps/control-plane/pom.xml apps/control-plane/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY libs libs
COPY apps/instance apps/instance
COPY apps/control-plane apps/control-plane

ARG APP=instance
# Тесты в образе не гоняем: это делает CI (там Docker для Testcontainers).
RUN mvn -B -q -pl apps/${APP} -am -DskipTests package \
 && cp apps/${APP}/target/${APP}-*.jar /build/app.jar

# Распаковка fat-jar: рядом появляются lib/ (зависимости) и запускаемый jar.
# Разделение нужно для кэша Docker: lib меняется редко, код — каждую сборку.
WORKDIR /layers
RUN java -Djarmode=tools -jar /build/app.jar extract --destination /layers \
 && mv /layers/app-*.jar /layers/run.jar 2>/dev/null || mv /layers/*.jar /layers/run.jar

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:25-jre AS runtime

# Hardening:non-root пользователь, только необходимые пакеты, чистый apt-кэш
RUN groupadd --system --gid 10001 dwh \
 && useradd  --system --uid 10001 --gid dwh --home-dir /app --shell /usr/sbin/nologin dwh \
 && apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
ARG APP=instance
ENV APP_NAME=${APP}

# Каталог данных под non-root. ВРЕМЕННО: файлы клиента лежат на диске узла
# (блокер C-3 AUDIT-03). До перехода на Garage/S3 (фаза P) этот путь ОБЯЗАН
# монтироваться томом — иначе пересоздание контейнера теряет файлы.
RUN mkdir -p /var/lib/dwh/storage && chown -R dwh:dwh /var/lib/dwh
ENV DWH_STORAGE_LOCAL_PATH=/var/lib/dwh/storage
VOLUME ["/var/lib/dwh"]

# Порядок COPY = порядок изменчивости (реже меняется — раньше): зависимости,
# затем код приложения. Правка кода не инвалидирует ~100 МБ слоя с библиотеками.
COPY --from=build --chown=dwh:dwh /layers/lib     ./lib
COPY --from=build --chown=dwh:dwh /layers/run.jar ./app.jar

USER dwh:dwh
EXPOSE 8080 9090

# Контейнерные умолчания JVM: heap от лимита памяти cgroup, не от хоста
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 \
-XX:+ExitOnOutOfMemoryError -XX:+UseZGC -XX:+UseCompressedOops \
-Djava.security.egd=file:/dev/./urandom -Duser.timezone=UTC"

# Health-check уровня контейнера. Оркестратор (Nomad, фаза P) использует
# свои проверки поверх тех же actuator-эндпоинтов.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=4 \
  CMD curl -fsS http://localhost:${MANAGEMENT_PORT:-9090}/actuator/health/readiness || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
