# Фронтенд: сборка Angular -> раздача через nginx.
# Собирается в ту же compose-группу, что и бэкенд (см. docker-compose.yml).

# ---------------------------------------------------------------- build
FROM node:24.22.1-alpine@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53 AS build
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
 && chown -R web:web /var/cache/nginx /var/lib/nginx /var/run/nginx /tmp/nginx /usr/share/nginx/html \
 && install -d -m 0755 /etc/nginx/smc

COPY --chown=web:web nginx.conf /etc/nginx/http.d/default.conf
# Security headers included by every location, and the start-up step that validates the operator's
# frame sources and trusted proxies into /run/nginx/smc-edge.conf (ADR-0034). /etc/nginx/smc exists
# before the copy: a directory that COPY --chmod creates takes the file mode and loses its search bit.
COPY --chown=root:root --chmod=0644 nginx/security-headers.conf nginx/spa-csp.conf nginx/api-csp.conf /etc/nginx/smc/
COPY --chown=root:root --chmod=0755 nginx/40-smc-edge-config.sh /docker-entrypoint.d/40-smc-edge-config.sh
COPY --from=build --chown=web:web /build/dist/web/browser /usr/share/nginx/html

USER web:web
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  # 127.0.0.1, а не localhost: busybox-wget идёт в IPv6 (::1), где nginx
  # не слушает, и health-check падал при работающем сервере.
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null 2>&1 || exit 1
