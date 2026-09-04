# ==============================================================================
# SmartupCMS Makefile (Automation Commands)
# ==============================================================================

.PHONY: help install build test test-m1 clean docker-up docker-down run-server

help:
	@echo "SmartupCMS Commands:"
	@echo "  make build          - Compile all Java modules and Angular apps"
	@echo "  make test           - Run the full PostgreSQL-backed test suite"
	@echo "  make test-m1        - Run M1 Instance & Bootstrap tests"
	@echo "  make docker-up      - Start Docker infrastructure (PostgreSQL 18)"
	@echo "  make docker-down    - Stop Docker infrastructure"
	@echo "  make migrate        - Run Flyway schema migrations on instance"
	@echo "  make run-server     - Start SmartupCMS backend (:8080)"

build:
	mvn clean package -DskipTests
	cd apps/web && npm run build

test:
	mvn test

test-m1:
	mvn test -Dtest=MigrationGateAndBootstrapTest,FlywayMigrationScriptIntegrityTest -Dsurefire.failIfNoSpecifiedTests=false

clean:
	mvn clean

docker-up:
	docker compose up -d postgres

docker-down:
	docker compose down

migrate:
	mvn -pl apps/server -Dspring.profiles.active=migrate spring-boot:run

run-server:
	mvn -pl apps/server spring-boot:run
