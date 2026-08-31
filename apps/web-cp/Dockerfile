# Панель управления: сборка Angular -> раздача через nginx.
# Собирается в ту же compose-группу, что и остальные сервисы.

# ---------------------------------------------------------------- build
FROM node:24.15.0-alpine AS build
WORKDIR /build

# Слой зависимостей кэшируется отдельно от исходников
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------------------------------------------------------------- runtime
FROM nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236 AS runtime

# nginx под non-root: стандартный образ пишет pid и кэш в каталоги root
RUN apk del nginx-module-acme nginx-module-geoip nginx-module-image-filter nginx-module-njs nginx-module-xslt \
 && sed -i 's/^nginx=.*/nginx/' /etc/apk/world \
 && apk add --upgrade --no-cache nginx \
 && apk upgrade --no-cache \
 && addgroup -g 10001 -S web && adduser -u 10001 -S web -G web \
 && rm -f /etc/nginx/conf.d/default.conf \
 && sed -i '/^user nginx;/d' /etc/nginx/nginx.conf \
 && mkdir -p /var/cache/nginx /var/lib/nginx /var/run/nginx /tmp/nginx /usr/share/nginx/html \
 && chown -R web:web /var/cache/nginx /var/lib/nginx /var/run/nginx /tmp/nginx /usr/share/nginx/html

COPY --chown=web:web nginx.conf /etc/nginx/http.d/default.conf
COPY --from=build --chown=web:web /build/dist/web-cp/browser /usr/share/nginx/html

USER web:web
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  # 127.0.0.1, а не localhost: busybox-wget идёт в IPv6 (::1), где nginx
  # не слушает, и health-check падал при работающем сервере.
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null 2>&1 || exit 1
