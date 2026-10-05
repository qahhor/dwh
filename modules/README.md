# External Modules

Directory for external SmartupCMS module JAR files.
Mounted as `/app/modules` in Docker and Compose.

## How to use

1. Generate a new external module:
   ```bash
   node tools/cms-cli/bin/cms.mjs module new <name> --external --title "<Title>"
   ```
2. Build the module:
   ```bash
   cd modules/<name>
   mvn package
   ```
3. Place the built JAR in this directory (`modules/`):
   ```bash
   cp modules/<name>/target/<name>-module-*.jar modules/
   ```
4. Run migrations and start the server:
   ```bash
   docker compose run --rm migrate
   docker compose up -d server
   ```
