FROM postgres:18-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2

# UID/GID 10001 intentionally matches the server image so its read-only mount
# can read the 0600 status file without weakening file permissions.
RUN apk upgrade --no-cache \
 && apk add --no-cache age aws-cli ca-certificates \
 && rm -f /usr/local/bin/gosu \
 && addgroup -S -g 10001 backup \
 && adduser -S -D -H -u 10001 -G backup backup \
 && mkdir -p /backups /status \
 && chown -R backup:backup /backups /status

COPY --chmod=0555 write-status.sh /usr/local/bin/write-status
COPY --chmod=0555 backup-loop.sh /usr/local/bin/backup-loop
COPY --chmod=0555 bootstrap-role.sh /usr/local/bin/bootstrap-role

USER backup:backup
WORKDIR /backups

ENTRYPOINT ["/usr/local/bin/backup-loop"]
