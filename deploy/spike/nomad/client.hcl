# Клиентский узел c1 — Nomad client. IP серверов заменить на реальные.

datacenter = "dc1"
region     = "spike"
data_dir   = "/opt/nomad/data"
bind_addr  = "0.0.0.0"

client {
  enabled = true

  servers = ["10.0.0.11", "10.0.0.12", "10.0.0.13"]

  # Данные PostgreSQL демо-клиента (ADR-0007 §2.2: stateful = host volume + pin к узлу)
  host_volume "pgdata-demo" {
    path      = "/opt/pgdata-demo"
    read_only = false
  }

  meta {
    node_class = "client-nodes" # для constraint в job'ах
  }
}

vault {
  enabled = true
  address = "https://10.0.0.11:8200"
}

plugin "docker" {
  config {
    allow_privileged = false
    volumes { enabled = false } # только host_volume, произвольные маунты запрещены
  }
}

log_level = "INFO"
