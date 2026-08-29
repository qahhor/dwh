# ==============================================================================
# DWH Platform Makefile (Automation Commands)
# ==============================================================================

.PHONY: help install build test test-m1 clean docker-up docker-down run-instance run-cp

help:
	@echo "DWH Platform Commands:"
	@echo "  make build          - Compile all Java modules and Angular apps"
	@echo "  make test           - Run full test suite (57 tests on PostgreSQL 18)"
	@echo "  make test-m1        - Run M1 Instance & Bootstrap tests"
	@echo "  make docker-up      - Start Docker infrastructure (PostgreSQL 18)"
	@echo "  make docker-down    - Stop Docker infrastructure"
	@echo "  make migrate        - Run Flyway schema migrations on instance"
	@echo "  make run-instance   - Start DWH Instance backend (:8080)"
	@echo "  make run-cp         - Start Control Plane backend (:8081)"

build:
	mvn clean package -DskipTests
	cd apps/web-instance && npm run build
	cd apps/web-cp && npm run build

test:
	mvn test

test-m1:
	mvn test -Dtest=MigrationGateAndBootstrapTest,CpHeartbeatWorkerTest,FlywayMigrationScriptIntegrityTest -Dsurefire.failIfNoSpecifiedTests=false

clean:
	mvn clean

docker-up:
	docker compose up -d postgres

docker-down:
	docker compose down

migrate:
	mvn -pl apps/instance -Dspring.profiles.active=migrate spring-boot:run

run-instance:
	mvn -pl apps/instance spring-boot:run

run-cp:
	mvn -pl apps/control-plane spring-boot:run
