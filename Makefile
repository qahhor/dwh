# ==============================================================================
# SmartupCMS developer commands (GNU make on Linux, macOS, WSL and the dev container)
# ==============================================================================
# The targets call the Maven wrapper (./mvnw), npm and scripts/dev/run-local.sh; nothing else needs installing besides
# JDK 25, Node.js of .node-version and Docker with Compose v2. On Windows without make, the same steps are
# scripts\dev\run-local.ps1 (up, migrate, down, status) and the commands of docs/onboarding.md.

MVNW := ./mvnw -B
RUN_LOCAL := scripts/dev/run-local.sh
E2E_COMPOSE := docker compose -f docker-compose.yml -f scripts/dev/e2e-mail.compose.yml

.DEFAULT_GOAL := help
.PHONY: help install dev start demo stop status infra migrate build test verify e2e clean reset

help:
	@echo "SmartupCMS commands:"
	@echo "  make dev       infrastructure in Compose, server from the built jar, ng serve; Ctrl+C stops"
	@echo "  make start     the same in the background (make stop ends it)"
	@echo "  make demo      start with the demo profile: users, projects, tasks, notes, orders"
	@echo "  make stop      stop the server, the web dev server and the infrastructure"
	@echo "  make status    what runs"
	@echo "  make infra     only PostgreSQL (both databases) and Mailpit"
	@echo "  make migrate   migrate both databases: the main one and pg-dwh"
	@echo "  make install   install the web dependencies (npm ci)"
	@echo "  make build     server jar and web bundle"
	@echo "  make test      server and web unit tests"
	@echo "  make verify    the server and web checks of CI (format, lint, audits, tests, build)"
	@echo "  make e2e       browser E2E suite on a fresh Compose stack with the mail stub"
	@echo "  make clean     build output of the server and the web application"
	@echo "  make reset     stop everything and delete the local databases and .local/"

install:
	cd apps/web && npm ci

dev:
	$(RUN_LOCAL) up

start:
	$(RUN_LOCAL) up --detach

demo:
	$(RUN_LOCAL) up --detach --demo

stop:
	$(RUN_LOCAL) down

status:
	$(RUN_LOCAL) status

infra:
	$(RUN_LOCAL) infra

migrate:
	$(RUN_LOCAL) migrate

build:
	$(MVNW) -DskipTests package
	cd apps/web && npm run build

test:
	$(MVNW) test
	cd apps/web && npm test

verify:
	$(MVNW) clean spotless:check verify
	cd apps/web && npm run -s typecheck && npm run -s lint && npm run -s i18n:audit && npm run -s aria:audit \
		&& npm run -s contrast:audit && npm run -s signals:audit && npm run -s api:audit && npm run -s comments:audit \
		&& npm test && npm run -s build

# The suite needs the first administrator's password in ADMIN_PASSWORD (e2e/README.md); the stack is the quick
# start's one with the mail stub.
e2e:
	$(E2E_COMPOSE) build
	$(E2E_COMPOSE) run --rm migrate
	$(E2E_COMPOSE) up -d --wait
	cd e2e && npm ci && npx playwright install chromium && npm test

clean:
	$(MVNW) clean
	rm -rf apps/web/dist apps/web/.angular

reset:
	$(RUN_LOCAL) down --volumes
