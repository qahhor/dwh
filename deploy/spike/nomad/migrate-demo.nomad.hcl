# Batch-job миграций (прообраз T-022): Flyway отдельным шагом, НЕ при старте приложения.
# Параметризован целевой версией схемы:
#   nomad job run migrate-demo.nomad.hcl        (регистрация)
#   nomad job dispatch -meta target=2 migrate-demo
#
# SQL-миграции вшиты template-блоками — спайку не нужен artifact-сервер.
# V3 — НАМЕРЕННО ломаная (NOT NULL на заполненной таблице): учение дня 4.

job "migrate-demo" {
  datacenters = ["dc1"]
  type        = "batch"

  parameterized {
    meta_required = ["target"]
  }

  constraint {
    attribute = "${meta.node_class}"
    value     = "client-nodes"
  }

  group "migrate" {
    count = 1

    # Миграция не ретраится молча: упала — видим и разбираемся
    restart {
      attempts = 0
      mode     = "fail"
    }

    task "flyway" {
      driver = "docker"

      config {
        image = "flyway/flyway:11-alpine"
        args  = [
          "-url=jdbc:postgresql://${PGHOST}:${PGPORT}/demo",
          "-user=demo",
          "-locations=filesystem:/local/sql",
          "-target=${NOMAD_META_target}",
          "-outOfOrder=false",
          "migrate",
        ]
      }

      vault {}

      template {
        destination = "secrets/flyway.env"
        env         = true
        data        = <<EOT
{{ with secret "kv/data/instances/demo/pg" }}
FLYWAY_PASSWORD={{ .Data.data.password }}
{{ end }}
{{ range nomadService "demo-pg" }}
PGHOST={{ .Address }}
PGPORT={{ .Port }}
{{ end }}
EOT
      }

      # ---- V1: базовая схема -------------------------------------------
      template {
        destination = "local/sql/V1__init.sql"
        data        = <<EOT
create table demo_items(
  id    bigint generated always as identity primary key,
  name  text not null,
  created_at timestamptz not null default now()
);
insert into demo_items(name) values ('первый'), ('второй'), ('третий');
EOT
      }

      # ---- V2: expand-миграция (обратно совместимая, ADR-0007 §2.3) ----
      template {
        destination = "local/sql/V2__add_note_expand.sql"
        data        = <<EOT
-- expand: nullable-колонка, старый код продолжает работать
alter table demo_items add column note text;
EOT
      }

      # ---- V3: НАМЕРЕННО ЛОМАНАЯ (учение) -------------------------------
      template {
        destination = "local/sql/V3__broken_drill.sql"
        data        = <<EOT
-- destructive: НЕ approved — нарушение правила линтера намеренное (учение дня 4).
-- NOT NULL без DEFAULT на заполненной таблице упадёт в рантайме:
alter table demo_items add column price numeric not null;
EOT
      }

      resources {
        cpu    = 300
        memory = 512
      }
    }
  }
}
