# syntax=docker/dockerfile:1

ARG NGINX_BASE=nginx:1.28-alpine@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236
FROM ${NGINX_BASE}

USER root

RUN apk del nginx-module-acme nginx-module-geoip nginx-module-image-filter nginx-module-njs nginx-module-xslt \
    && sed -i 's/^nginx=.*/nginx/' /etc/apk/world \
    && apk add --upgrade --no-cache nginx \
    && apk upgrade --no-cache \
    && chown -R nginx:nginx /var/cache/nginx /var/log/nginx /etc/nginx /tmp

USER nginx:nginx
EXPOSE 8080
