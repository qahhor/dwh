# Job демо-«клиента»: PostgreSQL 18 + приложение-заглушка.
# Проверяет три механизма ADR-0007:
#   1) stateful PG как pinned-задача с host volume;
#   2) schema-gate: приложение НЕ стартует, если версия схемы БД != ожидаемой (FR-INST-2);
#   3) canary-обновление app-группы с auto_revert.
#
# Запуск:  nomad job run -var app_version=1 -var expected_schema=1 client-demo.nomad.hcl

variable "app_version" {
  type    = string
  default = "1"
}

variable "expected_schema" {
  type    = string
  default = "1"
}

job "client-demo" {
  datacenters = ["dc1"]
  type        = "service"

  constraint {
    attribute = "${meta.node_class}"
    value     = "client-nodes"
  }

  # ------------------------------------------------------------------ PG
  group "pg" {
    count = 1

    volume "pgdata" {
      type      = "host"
      source    = "pgdata-demo"
      read_only = false
    }

    network {
      port "db" { static = 5432 }
    }

    task "postgres" {
      driver = "docker"

      config {
        image = "postgres:18"
        ports = ["db"]
      }

      volume_mount {
        volume      = "pgdata"
        destination = "/var/lib/postgresql/data"
      }

      vault {}

      # Секрет — только из Vault, на диск узла в открытом виде не попадает
      template {
        destination = "secrets/pg.env"
        env         = true
        data        = <<EOT
{{ with secret "kv/data/instances/demo/pg" }}
POSTGRES_PASSWORD={{ .Data.data.password }}
{{ end }}
POSTGRES_USER=demo
POSTGRES_DB=demo
EOT
      }

      resources {
        cpu    = 500
        memory = 1024
      }

      service {
        name     = "demo-pg"
        port     = "db"
        provider = "nomad" # спайк: без Consul, нативный SD

        check {
          type     = "tcp"
          interval = "10s"
          timeout  = "2s"
        }
      }
    }
  }

  # ------------------------------------------------------------------ APP
  group "app" {
    count = 1

    network {
      port "http" { to = 5678 }
    }

    # Canary: новый alloc поднимается РЯДОМ со старым; promote — вручную;
    # unhealthy → автоматический откат на прежнюю версию.
    update {
      canary            = 1
      max_parallel      = 1
      auto_promote      = false
      auto_revert       = true
      min_healthy_time  = "15s"
      healthy_deadline  = "2m"
      progress_deadline = "5m"
    }

    # SCHEMA-GATE (prestart): сравнивает версию схемы Flyway с ожидаемой.
    # Несовпадение → exit 1 → alloc unhealthy → canary откатывается.
    task "schema-gate" {
      lifecycle {
        hook    = "prestart"
        sidecar = false
      }

      driver = "docker"

      config {
        image   = "postgres:18" # ради psql
        command = "/bin/sh"
        args    = ["/local/check.sh"]
      }

      vault {}

      template {
        destination = "secrets/gate.env"
        env         = true
        data        = <<EOT
{{ with secret "kv/data/instances/demo/pg" }}
PGPASSWORD={{ .Data.data.password }}
{{ end }}
{{ range nomadService "demo-pg" }}
PGHOST={{ .Address }}
PGPORT={{ .Port }}
{{ end }}
EXPECTED_SCHEMA=${var.expected_schema}
EOT
      }

      template {
        destination = "local/check.sh"
        data        = <<EOT
#!/bin/sh
set -eu
ACTUAL=$(psql -U demo -d demo -tA -c \
  "select coalesce(max(version::int),0) from flyway_schema_history where success" \
  2>/dev/null || echo "NO_HISTORY")
echo "schema-gate: actual=$ACTUAL expected=$EXPECTED_SCHEMA"
if [ "$ACTUAL" != "$EXPECTED_SCHEMA" ]; then
  echo "schema-gate: FAIL — приложение не стартует на несовпадающей схеме (FR-INST-2)"
  exit 1
fi
echo "schema-gate: OK"
EOT
      }

      resources {
        cpu    = 100
        memory = 128
      }
    }

    # «Приложение» — отвечает своей версией; health-check для canary
    task "web" {
      driver = "docker"

      config {
        image = "hashicorp/http-echo:1.0"
        args  = [
          "-listen=:5678",
          "-text=demo app v${var.app_version} schema=${var.expected_schema}",
        ]
        ports = ["http"]
      }

      resources {
        cpu    = 100
        memory = 64
      }

      service {
        name     = "demo-app"
        port     = "http"
        provider = "nomad"

        check {
          type     = "http"
          path     = "/"
          interval = "5s"
          timeout  = "2s"
        }
      }
    }
  }
}
