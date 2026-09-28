FROM postgres:18-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873

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
