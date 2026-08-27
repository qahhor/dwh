# Узлы платформы p1..p3 — Nomad server (кворум из трёх).
# СПАЙК: ACL выключены (включаются в T-020). IP заменить на реальные.

datacenter = "dc1"
region     = "spike"
data_dir   = "/opt/nomad/data"
bind_addr  = "0.0.0.0"

advertise {
  # На каждом узле — его собственный адрес
  http = "{{ GetInterfaceIP \"eth0\" }}"
  rpc  = "{{ GetInterfaceIP \"eth0\" }}"
  serf = "{{ GetInterfaceIP \"eth0\" }}"
}

server {
  enabled          = true
  bootstrap_expect = 3

  server_join {
    retry_join = ["10.0.0.11", "10.0.0.12", "10.0.0.13"] # p1, p2, p3
  }
}

# Интеграция с Vault через workload identity (Nomad >= 1.7).
# Точный синтаксис сверить с документацией установленной версии.
vault {
  enabled = true
  address = "https://10.0.0.11:8200"   # или LB/локальный агент; для спайка можно http + tls_skip

  default_identity {
    aud = ["vault.io"]
    ttl = "1h"
  }
}

log_level = "INFO"
