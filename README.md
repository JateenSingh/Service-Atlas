# Service Atlas

Service Atlas is a local-first desktop-style web application that scans a folder of microservice
repositories (Scala/SBT in v1), extracts the dependencies between them from build files,
configuration and source code, and renders an interactive architecture diagram you can explore and
export to Lucidchart.

It is static analysis only: nothing is executed, nothing is modified, and nothing leaves your
machine unless you ask it to.

---

## Quickstart

**Requirements:** JDK 21, Node 20+ (for the UI build). Nothing else — the database is embedded.

```bash
git clone <this repo> && cd service-atlas

# Build and run (builds the Angular bundle and serves it from the backend)
./gradlew bootRun
```

Open <http://localhost:8080>, then:

1. **New Workspace** → give it a name and the path to the folder containing your cloned repos.
2. **Scan** → watch per-repository progress as it discovers and parses each service.
3. **Explore** → pan, zoom, filter, click a node for its endpoints and a click-through to the exact
   file and line each dependency came from.
4. **Export** → `.lucid` for Lucidchart, or SVG / PNG / JSON.

Backend-only, for faster iteration:

```bash
./gradlew :backend:bootRun -PskipFrontend
```

Building the single deployable artifact:

```bash
./gradlew bootJar          # → backend/build/libs/service-atlas.jar
java -jar backend/build/libs/service-atlas.jar
```

## Configuration

Everything has a working default. The knobs that matter:

| Setting | Default | Purpose |
|---|---|---|
| `service-atlas.data-dir` | `~/.service-atlas` | Where the embedded H2 database lives |
| `service-atlas.scan.max-depth` | `3` | How deep to look for repositories (per-workspace override in the UI) |
| `service-atlas.scan.ignored-directories` | `.git`, `target`, `node_modules`, … | Never descended into (per-workspace additions in the UI) |
| `server.port` | `8080` | HTTP port |

**PostgreSQL** instead of embedded H2:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/service_atlas \
SPRING_DATASOURCE_USERNAME=service_atlas \
SPRING_DATASOURCE_PASSWORD=... \
java -jar service-atlas.jar --spring.profiles.active=postgres
```

Same Flyway migrations, same schema ([ADR-002](docs/adr/ADR-002-persistence.md)).

**Lucidchart REST API export** (optional — the `.lucid` file download always works without it):

```bash
export LUCID_API_ENABLED=true
export LUCID_CLIENT_ID=...        # from your Lucid developer application
export LUCID_CLIENT_SECRET=...
export LUCID_TOKEN_KEY=...        # key used to encrypt stored OAuth tokens
```

Credentials are read from the environment only and are never written to the repository. When they
are absent, the API export option is hidden in the UI.

## What it detects

| Signal | Source | Confidence |
|---|---|---|
| Internal artifact dependency | `libraryDependencies` matching another discovered service | HIGH |
| Service URL / host / discovery key | `application.conf`, `reference.conf` | HIGH |
| HTTP client call | `ws.url`, `WSClient`, sttp, http4s, Akka/Pekko HTTP clients | MEDIUM |
| Kafka / RabbitMQ produce & consume | config and client APIs, via a topic node | MEDIUM |
| Cross-service package import | `import com.company.logusersvc._` | LOW |
| Exposed endpoints | Play `conf/routes` | informational |

Every edge carries the file and line it came from, and you can filter the whole view by confidence
if you only want to see what is certain.

Name matching is spelling-insensitive: `log-quote-svc`, `logQuoteSvc` and `LOG_QUOTE_SVC` are the
same service, and the client artifact `log-quote-svc-client` resolves to it. Ambiguous matches
resolve to nothing rather than to a guess — see
[docs/architecture.md](docs/architecture.md#33-ambiguity-resolves-to-nothing).

## Development

```bash
./gradlew :backend:test -PskipFrontend        # backend unit + integration tests
./gradlew :backend:check -PskipFrontend       # adds the parser/graph/export coverage gate (≥75%)
./gradlew :backend:test -PwithTestcontainers  # additionally runs the PostgreSQL schema test (needs Docker)

cd frontend && npm test                       # frontend unit tests
cd e2e && npx playwright test                 # end-to-end
```

`e2e/fixtures/` holds a set of realistic Scala/SBT services (Play, Akka HTTP, http4s, gRPC, a
multi-module build) wired together through build dependencies, config URLs, HTTP client calls and a
Kafka topic. Both the backend integration tests and the Playwright E2E scan it.

### Layout

```
backend/    Spring Boot API + parsing engine (Java 21, Gradle Kotlin DSL)
frontend/   Angular 19 app — signals, custom SVG canvas, elkjs layout in a worker
e2e/        Playwright tests and the fixture repository set
docs/       Architecture notes and ADRs
```

## Design notes

- [Architecture](docs/architecture.md) — pipeline, interpretation choices, safety posture
- [ADR-001](docs/adr/ADR-001-layout-engine.md) — why `elkjs`
- [ADR-002](docs/adr/ADR-002-persistence.md) — why embedded H2 with an optional PostgreSQL profile
- [ADR-003](docs/adr/ADR-003-lucidchart-export.md) — why the Standard Import file comes first

## Guarantees

- **Read-only.** Repositories are opened for reading and nothing else; a test hashes the whole
  fixture tree before and after a scan and fails if a single byte or timestamp moved.
- **Nothing is executed.** SBT is never invoked and no repository code is evaluated.
- **Local-first.** No account, no cloud sync, no telemetry. Workspaces export to a portable
  `.atlas` bundle that contains your graph and layout — never your source code.
