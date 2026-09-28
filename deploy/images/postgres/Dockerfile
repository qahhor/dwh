# syntax=docker/dockerfile:1

# Pinned upstream digest; update deliberately after image scanning.
ARG POSTGRES_BASE=postgres:18-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873
FROM ${POSTGRES_BASE}

USER root

# The upstream image currently ships outdated Alpine security packages and a
# vulnerable Go-built gosu binary. Database services run directly as postgres,
# so gosu is neither needed nor reachable at runtime.
RUN apk upgrade --no-cache \
    && rm -f /usr/local/bin/gosu

COPY --chmod=0755 init-roles.sh /docker-entrypoint-initdb.d/01-init-roles.sh
COPY --chmod=0755 init-dwh.sh /docker-entrypoint-initdb.d/02-init-dwh.sh

USER postgres
