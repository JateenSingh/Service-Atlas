# ADR-003: Lucidchart export — Standard Import file first, REST API second

- **Status:** Accepted
- **Date:** 2026-08-12
- **Deciders:** Service Atlas build agent
- **Related requirements:** FR-6.1 … FR-6.5, Q-3

## Context

The in-app SVG canvas is the primary visualisation (§1). Lucidchart is the *sharing* channel:
an engineer explores locally, then hands the team a diagram they can edit in a tool they already
use. Two mechanisms exist for getting a diagram into Lucid:

1. **Standard Import** — a `.lucid` file (a ZIP whose payload is `document.json`) that the user
   imports by hand via `File > Import > Lucid Standard Import`. No account linking, no
   credentials, no network call from Service Atlas.
2. **Lucid REST API** — `POST /documents` with the same Standard Import payload and
   `Content-Type` marking it as the Standard Import file type. Creates the document directly in
   the user's Lucid account, but requires OAuth 2.0, a registered Lucid application
   (client id/secret), and token storage.

## Decision

**Ship the Standard Import file as the primary, always-available path. Make the REST API an
optional, config-gated secondary path.**

1. `LucidDocumentBuilder` produces an in-memory `document.json` model from a filtered
   `DependencyGraph` + layout. It is the *single* place that knows Lucid's schema.
2. `LucidPackager` zips that JSON into a `.lucid` file. The download endpoint
   (`POST /api/v1/workspaces/{id}/export/lucid`) needs no credentials and always works offline.
3. `LucidApiClient` (`export/api/`) reuses the exact same builder output for the REST path. When
   `service-atlas.lucid.api.enabled` is false or the client id/secret are absent, the endpoint
   returns `409 Problem Details` and the frontend hides the option entirely (FR-6.2).
4. Credentials come only from externalised config / environment variables; OAuth tokens are
   encrypted before they reach `lucid_credentials` (Q-3). No secret is ever committed.

### On the schema itself (FR-6.5)

FR-6.5 requires verifying the Standard Import schema against official Lucid developer
documentation at build time rather than relying on memorised field names. **This was attempted and
partially blocked:** in the build environment, `developer.lucid.co`, `lucid.readme.io` and
`community.lucid.co` are all blocked by the network egress proxy, so the official reference pages
could not be read directly. The schema encoded in `LucidDocumentBuilder` was therefore
reconstructed from the sources that *were* reachable, cross-checked against each other:

- indexed excerpts of `developer.lucid.co/docs/lines-si`, `.../reference-si` and
  `.../overview-si` returned by web search;
- a third-party open-source implementation of the format
  (`github.com/gerkeac/lucid-import2-mcp`), whose TypeScript model and worked example agree with
  those excerpts.

Points corroborated by both sources and encoded in the builder: `.lucid` is a ZIP containing
`document.json`; top level is `{ version, pages[] }`; a page is `{ id, title, shapes[], lines[] }`;
a shape is `{ id, type, boundingBox: {x,y,w,h}, style: { fill: {type:"color", color}, stroke:
{color,width,style} }, text }`; a line is `{ id, lineType, endpoint1, endpoint2, stroke }` where an
endpoint is `{ type: "shapeEndpoint", style, shapeId, position: {x,y} }` with position normalised
to 0…1 of the shape's bounding box.

Consequently:

- Everything schema-shaped lives behind `LucidDocumentBuilder` and the `lucid/` DTO records.
  Nothing else in the codebase knows a Lucid field name.
- The DTOs serialise with `@JsonInclude(NON_NULL)` so unset optional fields are omitted rather
  than sent as `null` — the safest posture against a schema we could not fully verify.
- A golden-file test (`LucidDocumentBuilderTest`) pins the emitted JSON, so any future schema
  correction is a single, visible, well-tested diff.
- **Follow-up:** re-verify against the official reference the first time this runs in an
  environment with unrestricted egress. `docs/architecture.md` records this as an open item.

## Consequences

**Positive**

- Export works for every user immediately, with no Lucid app registration and no OAuth dance.
- One builder feeds both paths, so file export and API push can never drift apart.
- Zero secrets in the default configuration (Q-3 satisfied by construction).

**Negative**

- The primary path is a manual import step for the user. Mitigated by an in-app "how to import"
  hint after download (UX-5).
- The schema carries residual risk from the blocked-documentation situation above. Mitigated by
  localisation behind one builder, golden-file tests, and the recorded follow-up.

## Alternatives rejected

- **REST API only** — rejected: it makes the headline feature depend on credentials most users
  will not have, and breaks offline use.
- **Emit Lucid-flavoured CSV/VSDX** — lossy on styling and edge routing, and no better supported.
- **Mermaid / PlantUML instead** — text formats are useful, but they are not Lucidchart. Kept as
  a stretch goal (§9); the graph JSON export (FR-6.4) already covers "get the data out".
