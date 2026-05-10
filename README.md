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
