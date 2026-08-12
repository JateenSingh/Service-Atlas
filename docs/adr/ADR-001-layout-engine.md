# ADR-001: Graph layout engine

- **Status:** Accepted
- **Date:** 2026-08-12
- **Deciders:** Service Atlas build agent
- **Related requirements:** FR-5.2, FR-5.7, Q-5

## Context

Service Atlas renders a directed dependency graph of microservices on a custom SVG canvas
(FR-5.1). The rendering surface is hand-written, but *positions* still have to come from
somewhere. The graphs we expect are layered by nature — an edge means "A calls / depends on B" —
so a layered (Sugiyama-style) layout communicates the architecture far better than a force
directed one: it produces stable, readable left-to-right flow with minimal edge crossings.

Two options were considered:

1. **Hand-rolled Sugiyama implementation.** Full control, zero dependencies, but a correct
   implementation needs cycle breaking, layer assignment, crossing minimisation (ordering) and
   coordinate assignment — plus orthogonal edge routing to look decent. That is a multi-week
   project on its own and it is not the product's differentiator.
2. **`elkjs`** — the JS build of the Eclipse Layout Kernel. Its `layered` algorithm is a mature,
   well-tuned Sugiyama implementation with orthogonal routing, port constraints, node/edge
   spacing controls and both `RIGHT` and `DOWN` directions out of the box. It is a pure-JS
   artifact (no WASM/native), MIT-compatible (EPL-2.0), and runs happily inside a Web Worker.

The specification (§2) fixes this decision in favour of `elkjs`; this ADR records the reasoning
and, more importantly, the constraints we place on the dependency.

## Decision

Use **`elkjs` with the `layered` algorithm** for automatic layout.

Constraints on how it is used:

- `elkjs` is **never** referenced directly by canvas or store code. It sits behind a
  `LayoutEngine` interface (`frontend/src/app/features/canvas/layout/layout-engine.ts`) that
  speaks only in Service Atlas domain terms (`LayoutRequest` → `LayoutResult`).
- Layout runs in a **Web Worker** (`layout.worker.ts`). ELK on a 100-node graph takes tens to
  hundreds of milliseconds; doing that on the UI thread would break the interaction target in
  FR-5.7 / Q-5.
- Manual position overrides (FR-4.4) are applied *after* layout as a post-pass, so a user drag
  is never fought by the layout engine.
- The worker is the only place that imports `elkjs`, so swapping the engine means writing one
  new `LayoutEngine` implementation and changing one factory.

## Consequences

**Positive**

- Readable layered diagrams on day one, with edge routing we would otherwise have to write.
- `RIGHT` (default) / `DOWN` direction switching (FR-5.2) is a single ELK option.
- The worker boundary keeps the UI responsive during re-layout, and gives us a natural place to
  cancel superseded layout requests.

**Negative**

- ~1 MB of JS added to the bundle. Mitigated: the worker chunk is loaded lazily, only when a
  graph is first rendered, so it does not affect the Q-5 startup budget.
- ELK's option names are stringly-typed and version-sensitive. Mitigated: every option we set is
  defined once in `elk-layout-engine.ts` with a comment, and the engine's output is validated
  (finite coordinates, all requested nodes present) before it reaches the canvas.
- Asynchronous layout means the canvas must tolerate "graph loaded but not yet positioned".
  Handled with an explicit `layoutPending` signal and a skeleton state rather than rendering
  nodes at (0, 0).

## Alternatives rejected

- **Hand-rolled Sugiyama** — rejected on cost/benefit; revisit only if ELK's bundle size or
  routing quality becomes a real problem.
- **d3-force / force-directed** — rejected: non-deterministic, poor for dependency direction,
  and it makes "same scan, same picture" impossible.
- **Dagre** — smaller and simpler, but effectively unmaintained and noticeably weaker at
  orthogonal routing and port placement than ELK.
