# Service Atlas — Architecture

This document explains how Service Atlas is put together, and records the interpretation choices
made while implementing the specification (§11: "record the choice in `docs/architecture.md`").

For the three load-bearing decisions, see the ADRs:

- [ADR-001](adr/ADR-001-layout-engine.md) — `elkjs` for graph layout
- [ADR-002](adr/ADR-002-persistence.md) — embedded H2 by default, PostgreSQL optional
- [ADR-003](adr/ADR-003-lucidchart-export.md) — Standard Import file first, REST API second

---

## 1. Shape of the system

```
                 ┌────────────────────────── browser ──────────────────────────┐
                 │  Angular 19 (standalone components, signals)                 │
                 │                                                              │
                 │   workspace ── canvas (custom SVG) ── inspector ── export     │
                 │        │            │                                         │
                 │        │            └── layout.worker.ts → elkjs              │
                 │        │                                                      │
                 └────────┼──────────────────────────────────────────────────────┘
                          │ REST /api/v1 + SSE progress
                 ┌────────┴──────────────── Spring Boot (Java 21) ──────────────┐
                 │                                                              │
                 │  api/ ── workspace/ ── scan/ ─┬─ parser/ (SPI)               │
                 │                               │     └── scala/ scanners      │
                 │                               └─ graph/ (builder + store)    │
                 │                                        │                     │
                 │                             export/ ───┘   persistence/      │
                 └──────────────────────────────────┬───────────────────────────┘
                                                    │ JDBC + Flyway
                                            H2 (file) or PostgreSQL
```

One process. `./gradlew bootJar` builds the Angular bundle, copies it into the jar's
`static/`, and produces a single runnable artifact that serves both the API and the UI.

## 2. The extraction pipeline

The core flow is four stages, deliberately kept apart:

1. **Discover** (`scan/RepoDiscoverer`) — walk the workspace root to a bounded depth, looking for
   `build.sbt` or `project/build.properties`. A directory that matches is a repository and is *not*
   descended into: a multi-module build is one repository with modules, not a folder of repos.
2. **Parse** (`parser/`) — for each repository, one `LanguageParser` produces nodes plus *unresolved*
   `DependencySignal`s. A signal says "this repo references something called `log-quote-svc`"; it
   does not say what that resolves to, because a parser only ever sees one repository.
3. **Correlate** (`graph/GraphBuilder`) — with every repository parsed, resolve each signal's target
   against an `AliasIndex` of all discovered names, and fold signals into edges.
4. **Persist** (`graph/GraphStore`) — write nodes and edges for that scan version.

This split is what makes FR-3.8 real: adding a Kotlin/Gradle parser means adding one bean that
implements `LanguageParser`. Discovery, correlation, persistence and the API are untouched, because
none of them know anything about Scala.

### Why signals are unresolved

The alternative — parsers that resolve targets themselves — needs every parser to see every other
repository, which couples parsers to each other and to scan ordering. Keeping resolution in one
language-agnostic place also means the name-matching rules (FR-3.2's `log-quote-svc` ≈ `logQuoteSvc`
≈ `LOG_QUOTE_SVC`) are implemented and tested exactly once.

### Confidence and evidence

Every signal carries a `SignalSource`, which fixes its default `Confidence` and `EdgeType`, and an
`Evidence` record (file, 1-based line, snippet, explanation). Evidence is not decoration: a MEDIUM
edge from a source-code scan is a claim the user needs to be able to check, and the inspector panel
shows exactly which line produced it.

When several signals support the same (source, target, type), they collapse into one edge that
accumulates evidence and takes the *strongest* confidence.

## 3. Interpretation choices

These are the places where the specification left room, and what was chosen.

### 3.1 Regex/line parsers rather than `scalameta` (FR-3.7)

FR-3.7 allows "lightweight AST via `scalameta` if practical from the JVM; otherwise well-tested
regex/line parsers with fixtures". `scalameta` is a Scala library; consuming it from Java means
pulling the Scala runtime and Scala collection interop into a Java codebase, for parse trees we
would then have to walk with Scala pattern matching from Java. That is not practical, and it fights
§11's "keep the dependency footprint small".

So: **regex and line parsers, with fixtures**, as the specification's fallback allows. Two things
make that defensible rather than fragile:

- `TextSource` strips comments (respecting string literals) before matching, so a commented-out
  `ws.url(...)` cannot produce a phantom edge — the classic regex-extractor failure.
- Every rule is fixture-driven against realistic `build.sbt`, `application.conf` and `routes` files
  committed under `backend/src/test/resources/fixtures/` and `e2e/fixtures/`.

`build.sbt` is a Scala program and a regex reader cannot be complete. The parser reads `build.sbt`,
sibling `*.sbt` files and `project/*.scala` (where the common `Dependencies.scala` pattern lives),
which covers how these builds are written in practice.

### 3.2 External nodes are created selectively (FR-4.1)

FR-4.1 defines `EXTERNAL` as "a referenced service not found in the scanned folder". Applied to
*every* unresolved reference, that would turn each third-party `libraryDependencies` entry —
scalatest, logback, cats — into a node, and bury the architecture.

The rule: only signal sources that reference a *host* create external nodes (config references and
HTTP client calls). Build dependencies and source imports resolve against discovered services or
are dropped. Localhost and bare IP addresses never become nodes. See
`SignalSource.createsExternalNodes()`.

### 3.3 Ambiguity resolves to nothing

`AliasIndex` keeps two indexes: exact canonical names, and "stems" with trailing `-svc` / `-service`
/ `-client` noise removed (so the artifact `log-quote-svc-client` finds the service `log-quote-svc`).
If two services share a stem, that stem resolves to **nothing** rather than to a guess. A missing
edge is a smaller problem than a confidently wrong one, and the user can add the edge by hand
(FR-4.4).

Names shorter than five characters, and pure noise words (`api`, `svc`, `core`), are never
matchable — they produce false positives in every real repository set.

### 3.4 Schema additions beyond §6

§6 lists the core tables. Two additions were needed to satisfy functional requirements:

- **`scan_repo`** — per-repository status, message, duration and content hash. FR-7.1 (live
  per-repo progress), FR-7.2 (per-repo failure without failing the scan) and FR-7.3 (incremental
  re-scan) all need per-repo state, and none of §6's tables can hold it.
- **`workspace.settings_json`** — FR-2.1 makes scan depth configurable and FR-2.3 makes the ignore
  list user-configurable. Storing them as JSON keeps future knobs migration-free.

Node `metadata_json` carries three things at once — free-form metadata, the endpoint catalogue
(FR-3.4) and warnings (FR-7.2) — rather than adding columns for each. None of it is ever queried in
SQL, which is exactly the condition under which a JSON payload column is the right choice.

### 3.5 Content hashing is two-tier (FR-7.3)

FR-7.3 asks for "content hashing of `build.sbt`, `conf/**`, and source dirs". Build and config files
are hashed by content — they are small and carry most of the signal. Scala sources are hashed by
path, size and modification time: reading every source file of twenty repositories in order to
decide whether to read them again defeats the purpose. This is the standard incremental-build
heuristic; it misses only edits that preserve both size and mtime.

### 3.6 Scans run one-at-a-time per workspace

Nothing in the specification demands concurrent scans of the same workspace, and allowing them
would mean two writers racing on the same scan-versioned tables. A second `POST /scans` for a
workspace with a scan in flight returns `409`.

### 3.7 Sub-modules become nodes only when deployable (FR-2.4)

FR-2.4 says to treat "each module with its own routes/main class as a sub-service". So a module is
promoted to a `SUB_MODULE` node when it has `conf/routes` or a `Main`/`Boot`/`Server` class; a
library-only module stays invisible, as part of its parent.

### 3.8 Datastores are keyed by engine and database, not by host (FR-3.9)

A datastore node's identity is `engine + database name`. Deliberately *not* the hostname: the same
database is reached as `orders-db.prod.svc.cluster.local` from one service, through `pgbouncer` from
another, and from an environment variable in a third. Keying on the host would draw three databases
and hide the one fact worth drawing — that two services share a store, which is coupling. Where the
connection string names no database, the host is the fallback identity.

Where **nothing** names it — a URL assembled from environment variables, or only a driver on the
classpath — identity falls back to the *owning service*, and the node is labelled `<service> db` with
the engine as its subtitle. The tempting fallback, keying on the engine, is wrong in a way worth
spelling out: it merges every service that happens to use PostgreSQL into one node called
"PostgreSQL", inventing exactly the false coupling the host rule above exists to avoid. Convergence
has to be earned by a name two services actually share.

Three tiers of evidence feed it, weighted differently on purpose:

| Tier | What it reads | Confidence |
|---|---|---|
| Configuration | connection strings; `dbname`/`database`/`keyspace` keys; driver classes and Slick profiles | HIGH |
| Schema ownership | Flyway migrations, Play evolutions | HIGH |
| Driver dependency | a driver coordinate in `libraryDependencies` | LOW |

The first tier reads a config *block* rather than a single key, because real configuration spreads
the facts around: `db.default` typically carries the driver class on one line, a URL built from
`${?DB_HOST}` on the next, and the database name on a third — and Slick pushes the name two levels
down into `properties.databaseName`. Read key-by-key that is three datastores; read per block it is
one. A URL that resolves to `jdbc:postgresql://:5432/` is still parsed, because the engine in it is
certain even when nothing else is, and an unresolved `${...}` is never treated as a name.

The middle tier exists because the services with the most disciplined configuration would otherwise
be the ones drawn with no database at all. Migrations attach to the connection the service already
configured when there is exactly one relational candidate, so a service with both a JDBC URL and a
migration folder has one database rather than two. The driver tier is emitted **only** when the
first two found nothing, so a service is never shown with both a real database and a vague one; and
`ConfigReferenceScanner` defers to `ConnectionStrings`, so `mongodb://inventory-db/inventory` is a
database rather than an external HTTP service.

Local development stores — embedded H2, anything on `localhost` — are dropped. They are not
architecture. Datastores can also be hidden wholesale from the filter panel, for the readings of a
diagram that are about services talking to each other.

### 3.9 A Pub/Sub subscription is not a topic (FR-3.10)

Google Pub/Sub flows are drawn through the same `TOPIC` node as Kafka and RabbitMQ, tagged with the
broker, because a reader wants "`order-events-v2` sits between these services" regardless of the
technology carrying it.

**Recognising it.** A `topic` key is only Pub/Sub if something says so, or the same rule would claim
every Kafka topic in the estate. Three things count: a `projects/…/topics/…` resource path, which is
self-identifying wherever it appears and needs no help from the key name; a Pub/Sub client in
`libraryDependencies`, which vouches for a bare `topic` key in that repository; and a `pubsub`/`gcp`
segment in the key path. Requiring the last of those alone was too strict — plenty of configuration
writes `messaging.orders.topic` — and silently produced no Pub/Sub at all.

**Direction.** A subscription is a named cursor onto a topic, and consuming services declare the
*topic* name in their config too, so reading every `topic` key as a publish inverts half the diagram.
Direction is decided in order: the key path when it says `publisher`/`consumer` outright; then a
topic with no sibling subscription, which is a publish; then a topic *with* one, which is set aside
rather than drawn, because it is as likely to be naming what the service reads.

Setting it aside would lose the common case where a service publishes to a topic it also configures a
subscription for, and names it in code only as `config.getString("…")`. So one inference closes that
gap: a service that builds a publisher, publishes to nothing else we could name, and has exactly
*one* set-aside topic, publishes that topic — recorded at LOW confidence with evidence saying why.
Two set-aside topics stop there, because picking one would be a guess (§3.3).

The topic/subscription pairing also resolves source-level references, where
`ProjectSubscriptionName.of(project, "order-events-inventory-sub")` names only the subscription; the
config knows which topic it reads, which keeps one node per topic instead of one per subscriber.
Names held in a `val` — including a qualified `Topics.Renders` — are followed the same way the HTTP
scanner follows them. When no pairing is available the subscription becomes its own node — honest
about a real flow rather than inventing a link to a topic we cannot see.

## 4. Frontend structure

- **State** is Angular signals throughout — no NgRx (§2). Stores are plain injectable classes
  exposing `signal()` and `computed()`; the canvas re-renders reactively from them.
- **Layout** runs in a Web Worker behind the `LayoutEngine` interface (ADR-001), so a 100-node
  re-layout never blocks interaction.
- **The canvas is hand-written SVG** (§2 forbids third-party diagram widgets). Pan/zoom is a single
  transform on a root `<g>`; nodes and edges are plain SVG elements, which keeps hit-testing,
  theming and SVG export trivial.

## 5. Security and safety posture

- **Read-only by construction** (Q-4). Parsers touch the filesystem only through `RepoFiles`, which
  exposes no write, delete or create operation, refuses paths that escape the repository root
  (including via symlink), and enforces file-count and file-size budgets. `ReadOnlyScanTest` hashes
  the entire fixture tree before and after a scan and asserts it is unchanged.
- **No repository code is ever executed** (FR-3.7). Nothing invokes SBT, and nothing evaluates
  Scala.
- **No secrets in the repository** (Q-3). Lucid credentials come from environment variables only;
  stored OAuth tokens are encrypted; the export UI hides the API path entirely when it is not
  configured.

## 6. Testing

| Layer | What it covers | How to run |
|---|---|---|
| Backend unit + integration | Parsers against fixture repos, graph correlation, overlays, bundles, exports, the HTTP contract | `./gradlew :backend:test -PskipFrontend` |
| Coverage gate (Q-1) | ≥75% line coverage on `parser/`, `graph/`, `export/` | `./gradlew :backend:check -PskipFrontend` |
| PostgreSQL schema | ADR-002's "identical schema" claim, on a real Postgres | `./gradlew :backend:test -PwithTestcontainers` (needs Docker) |
| Frontend unit | Filter derivations and viewport arithmetic | `cd frontend && npm test` |
| End-to-end (Q-2) | Create workspace → scan → explore → export `.lucid`, and a manual edit surviving a re-scan | `cd e2e && npx playwright test` |

Two tests are worth calling out because they assert claims made elsewhere in this document:

- `ReadOnlyScanTest` hashes the whole fixture tree before and after a scan and fails if a single
  byte or timestamp moved (Q-4).
- `LucidExportTest` is the golden-file test ADR-003 promises, pinning the emitted Lucid structure so
  a future schema correction is one visible diff.

The E2E suite runs the packaged jar — the same artifact a user runs — against a throwaway database.
Where a pre-installed browser is available, point `PLAYWRIGHT_CHROMIUM_PATH` at it.

## 7. Measured performance (Q-5)

Measured on the build container, which is a CPU-limited shared VM — slower than the "typical dev
machine" Q-5 refers to:

| Budget | Target | Measured here |
|---|---|---|
| Startup to interactive UI | < 5 s | ~6.3 s |
| Scan of 20 medium repositories | < 60 s | 27 repositories in **1.1 s** |

The scan budget is met with a very large margin: the whole pipeline is I/O-bound reading small
files, and the slowest single repository in that run took 72 ms.

Startup is dominated by JVM and Spring context initialisation, not by anything Service Atlas does.
`spring.main.lazy-initialization=true` is enabled for exactly this reason — most beans here (parsers,
exporters, the Lucid client) are only needed once a scan or an export runs, so building them eagerly
costs startup time and buys nothing; the first API call after boot measured 0.3 s. On typical
developer hardware this lands inside the 5-second budget; on this container it does not, and that is
recorded here rather than rounded away.

## 8. Open items

- **Lucid Standard Import schema re-verification.** FR-6.5 requires checking the schema against
  official Lucid documentation at build time. In the build environment `developer.lucid.co`,
  `lucid.readme.io` and `community.lucid.co` are blocked by the network egress proxy, so the schema
  was reconstructed from reachable secondary sources and cross-checked (see ADR-003). It should be
  re-verified against the official reference in an environment with unrestricted egress. Everything
  schema-shaped is confined to `LucidDocumentBuilder` and pinned by a golden-file test, so a
  correction is a single localised diff.
