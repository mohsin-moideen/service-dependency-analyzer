# sda-mcp

MCP server exposing the Service Dependency Analyzer's read queries as tools an LLM can call. Talks to a running SDA instance over HTTP — start the service first (`./gradlew bootRun`), then point the MCP server at it.

## Tools

| Tool | Endpoint |
|---|---|
| `reachable` | `GET /api/v1/graph/reachable/{service}` |
| `dependents` | `GET /api/v1/graph/dependents/{service}` |
| `shortest_path` | `GET /api/v1/graph/shortest-path?source=&target=` |
| `critical_services` | `GET /api/v1/graph/critical-services?k=` |
| `cycles` | `GET /api/v1/graph/cycles` |
| `health` | `GET /api/v1/graph/health/{service}?windowSeconds=` |

Read-only by design. Event ingest is intentionally not exposed.

## Build

```bash
cd mcp
npm install
npm run build
```

## Configure

Environment variables:

- `SDA_BASE_URL` — base URL of the SDA service (default `http://localhost:8080`).
- `SDA_TIMEOUT_MS` — per-request timeout in ms (default `10000`).

### Claude Desktop / Claude Code — local Node

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (or the equivalent `~/.claude/mcp.json` for Claude Code):

```json
{
  "mcpServers": {
    "sda": {
      "command": "node",
      "args": ["/absolute/path/to/Service Dependency Analyzer/mcp/dist/index.js"],
      "env": {
        "SDA_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

Restart the client. The six tools appear under the `sda` server.

## Docker

`docker-compose.yml` at the repo root runs both the SDA service and an MCP sidecar:

```bash
docker compose up -d        # builds + starts `sda` and `sda-mcp`
docker compose ps           # both should be Up; sda also reports `healthy`
docker compose down         # stop everything
```

The sidecar runs no daemon — stdio MCP servers are spawned per-session. The container exists so clients have something to `docker exec` into. It reaches the SDA via the compose network at `http://sda:8080` (set by `SDA_BASE_URL` in `mcp/Dockerfile`).

### Claude Desktop / Claude Code — via the sidecar

```json
{
  "mcpServers": {
    "sda": {
      "command": "docker",
      "args": ["exec", "-i", "sda-mcp", "node", "/app/dist/index.js"]
    }
  }
}
```

`docker compose up -d` must be running before the client launches its MCP session, otherwise the `exec` fails. If you'd rather not keep a sidecar around, swap to `docker run --rm -i --network <compose-network> sda-mcp:latest node /app/dist/index.js` — a fresh container per session, same image.

## Errors

Tool calls that hit a 4xx/5xx surface as `isError: true` with the SDA `ApiError` body (`error`, `message`, `service`) plus the HTTP status — the LLM gets enough context to decide whether to retry, ask for a different service id, or give up. Connection failures include a hint pointing at `SDA_BASE_URL`.
