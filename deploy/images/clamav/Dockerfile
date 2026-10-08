# syntax=docker/dockerfile:1

# Pinned upstream digest (official multi-architecture index); update deliberately after image scanning.
ARG CLAMAV_BASE=clamav/clamav-debian:1.5.4@sha256:df80497be841a8ad57f95e04f978216241457f8f8ad608f1f682e3cd0fe63c45
FROM ${CLAMAV_BASE}

LABEL org.opencontainers.image.title="SmartupCMS ClamAV" \
      org.opencontainers.image.description="Official ClamAV 1.5.4 (Debian) with current Debian security updates"

# The upstream image lags behind Debian security updates (OpenSSL, PCRE2). The upgrade takes them; nothing else
# changes. The upstream /init stays the entrypoint with the upstream user (root): it chowns the signature volume
# and writes every CLAMD_CONF_<Option> into /etc/clamav/clamd.conf, then clamd drops to the clamav user
# (User clamav) and freshclam runs with --user=clamav.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y --no-install-recommends \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
