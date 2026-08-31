# syntax=docker/dockerfile:1

ARG TYPESENSE_BASE=typesense/typesense:27.1@sha256:5c12af89130b8ee0be11541321ba8a3a7c7a538d7c6cd95e0409dc2d75ca6455
FROM ${TYPESENSE_BASE}

USER root

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y --no-install-recommends \
    && groupadd --system --gid 10002 typesense \
    && useradd --system --uid 10002 --gid typesense --no-create-home --shell /usr/sbin/nologin typesense \
    && mkdir -p /data \
    && chown -R typesense:typesense /data \
    && rm -rf /var/lib/apt/lists/*

USER typesense:typesense
VOLUME ["/data"]
