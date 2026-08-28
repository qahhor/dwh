# Фронтенд: сборка Angular -> раздача через nginx.
# Собирается в ту же compose-группу, что и бэкенд (см. docker-compose.yml).

# ---------------------------------------------------------------- build
FROM node:24-alpine AS build
WORKDIR /build

# Слой зависимостей кэшируется отдельно от исходников
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------------------------------------------------------------- runtime
FROM nginx:1.27-alpine AS runtime

# nginx под non-root: стандартный образ пишет pid и кэш в каталоги root
RUN addgroup -g 10001 -S web && adduser -u 10001 -S web -G web \
 && mkdir -p /var/cache/nginx /tmp/nginx \
 && chown -R web:web /var/cache/nginx /tmp/nginx /usr/share/nginx/html \
 && sed -i 's|^pid .*|pid /tmp/nginx/nginx.pid;|' /etc/nginx/nginx.conf

COPY --chown=web:web nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=web:web /build/dist/web-instance/browser /usr/share/nginx/html

USER web:web
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  # 127.0.0.1, а не localhost: busybox-wget идёт в IPv6 (::1), где nginx
  # не слушает, и health-check падал при работающем сервере.
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null 2>&1 || exit 1
