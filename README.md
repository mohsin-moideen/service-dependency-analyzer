# Service Dependency Analyzer

In-memory directed graph of service dependencies, fed by a custom event queue, with REST queries for blast radius, reverse dependencies, shortest path, criticality, cycles, and health.

See [`CLAUDE.md`](./CLAUDE.md) for architecture and module layout. The full take-home spec lives at `~/Downloads/backend-take-home-assessment.md`.

## Quick start

```bash
./gradlew bootRun
```

Once running:
- Swagger UI:      http://localhost:8080/swagger-ui.html
- OpenAPI JSON:    http://localhost:8080/v3/api-docs
- Actuator health: http://localhost:8080/actuator/health

## Configuration

Knobs in `src/main/resources/application.yml`:

| Key | Default | Purpose |
|---|---|---|
| `sda.ingest.producer-count` | 2 | Producer threads |
| `sda.ingest.consumer-count` | 2 | Consumer threads |
| `sda.ingest.queue-capacity` | 10000 | Bounded queue capacity |
| `sda.health.window-seconds` | 300 | Trailing window for `health()` |

## Build

```bash
./gradlew build       # compile + test
./gradlew test        # tests only
./gradlew bootJar     # runnable jar in build/libs/
```

## Docker

```bash
docker compose up -d      # builds + starts the SDA service (and the MCP sidecar — see below)
docker compose ps         # `sda` should report healthy on :8080
docker compose down
```

## MCP server

[`mcp/`](./mcp) contains a TypeScript MCP server that exposes the six read queries (`reachable`, `dependents`, `shortest_path`, `critical_services`, `cycles`, `health`) as tools an LLM client (Claude Desktop / Claude Code) can call. Read-only by design — event ingest is intentionally not exposed. Talks to a running SDA instance over HTTP (`SDA_BASE_URL`, default `http://localhost:8080`).

Two ways to run it:

**Local Node** — `cd mcp && npm install && npm run build`, then point your MCP client at `node /abs/path/to/mcp/dist/index.js`.

**Sidecar in Docker Compose** — `docker compose up -d` brings up both `sda` and an `sda-mcp` container preloaded with the built server. Stdio MCP servers are spawned per-session by the client, so the sidecar stays idle until something attaches via `docker exec -i sda-mcp node /app/dist/index.js`. The sidecar reaches the service over the compose network at `http://sda:8080`.

Full setup, env vars, and Claude Desktop / Code config snippets in [`mcp/README.md`](./mcp/README.md).
