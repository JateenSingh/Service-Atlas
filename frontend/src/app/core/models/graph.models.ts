/**
 * TypeScript mirrors of the backend's JSON contract (§5, §6).
 *
 * Kept as plain interfaces deliberately: these are wire shapes, and the moment they grow behaviour
 * they stop matching what the server actually sends.
 */

export type NodeType = 'SERVICE' | 'SUB_MODULE' | 'TOPIC' | 'DATASTORE' | 'EXTERNAL';
export type EdgeType = 'HTTP' | 'ARTIFACT' | 'MESSAGING' | 'PERSISTENCE' | 'UNKNOWN';
export type Confidence = 'LOW' | 'MEDIUM' | 'HIGH';

export type SignalSource =
  | 'BUILD_DEPENDENCY'
  | 'CONFIG_REFERENCE'
  | 'HTTP_CLIENT_CALL'
  | 'SOURCE_IMPORT'
  | 'MESSAGING_PRODUCER'
  | 'MESSAGING_CONSUMER'
  | 'DATASTORE_CONNECTION'
  | 'DATASTORE_SCHEMA'
  | 'DATASTORE_DRIVER'
  | 'PUBSUB_PUBLISHER'
  | 'PUBSUB_SUBSCRIBER'
  | 'MANUAL';

export type ScanStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export type RepoScanStatus =
  | 'DISCOVERED'
  | 'PARSING'
  | 'DONE'
  | 'FAILED'
  | 'UNCHANGED'
  | 'SKIPPED';

export interface Endpoint {
  method: string;
  path: string;
  handler?: string;
  deprecated?: boolean;
}

export interface Evidence {
  source: SignalSource;
  file: string;
  line: number;
  snippet?: string;
  detail?: string;
}

export interface GraphNode {
  key: string;
  displayName: string;
  type: NodeType;
  framework?: string;
  scalaVersion?: string;
  sbtVersion?: string;
  repoPath?: string;
  parentKey?: string;
  description?: string;
  endpoints: Endpoint[];
  warnings: string[];
  metadata: Record<string, unknown>;
}

export interface GraphEdge {
  id: string;
  sourceKey: string;
  targetKey: string;
  type: EdgeType;
  confidence: Confidence;
  label?: string;
  evidence: Evidence[];
}

export interface DependencyGraph {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface GraphResponse {
  scanId: number | null;
  nodeCount: number;
  edgeCount: number;
  graph: DependencyGraph;
  /** User-pinned node positions (FR-5.3). */
  positions: Record<string, { x: number; y: number }>;
  /** Disagreements between the newest scan and the stored overlay (FR-4.4). */
  conflicts: OverlayConflict[];
  /** Node keys currently hidden in the overlay (FR-4.4). */
  hiddenNodes?: string[];
  /** Edge ids currently hidden in the overlay (FR-4.4). */
  hiddenEdges?: string[];
}

export type ConflictKind =
  | 'HIDDEN_EDGE_REAPPEARED'
  | 'MANUAL_EDGE_CONFIRMED'
  | 'OVERLAY_TARGET_MISSING';

export interface OverlayConflict {
  kind: ConflictKind;
  target: string;
  message: string;
}

/** Body of PATCH /workspaces/{id}/graph/overlay — every field optional (FR-4.4). */
export interface OverlayPatch {
  positions?: Record<string, { x: number; y: number }>;
  addedNodes?: Array<{ key: string; displayName: string; type: NodeType; note?: string }>;
  hiddenNodes?: string[];
  addedEdgeRequests?: Array<{
    sourceKey: string;
    targetKey: string;
    type: EdgeType;
    label?: string;
  }>;
  hiddenEdges?: string[];
  nodeNotes?: Record<string, string>;
  edgeNotes?: Record<string, string>;
  removePositions?: string[];
  removeAddedNodes?: string[];
  removeHiddenNodes?: string[];
  removeAddedEdges?: string[];
  removeHiddenEdges?: string[];
  removeNodeNotes?: string[];
  removeEdgeNotes?: string[];
  clear?: boolean;
}

export interface AtlasManifest {
  version: number;
  name: string;
  rootPath: string;
  exportedAt: string;
  nodeCount: number;
  edgeCount: number;
}

export interface WorkspaceSettings {
  maxDepth: number | null;
  ignoredDirectories: string[];
}

export interface ScanSummary {
  id: number;
  status: ScanStatus;
  startedAt: string;
  finishedAt: string | null;
  repoCount: number;
  errorCount: number;
  message: string | null;
}

export interface Workspace {
  id: number;
  name: string;
  rootPath: string;
  settings: WorkspaceSettings;
  createdAt: string;
  updatedAt: string;
  latestScan: ScanSummary | null;
}

export interface WorkspaceDetail {
  workspace: Workspace;
  scans: ScanSummary[];
}

export interface RootPathPreview {
  rootPath: string;
  repoCount: number;
  sampleRepoPaths: string[];
}

export interface RepoProgress {
  repoPath: string;
  displayName: string | null;
  status: RepoScanStatus;
  message: string | null;
  durationMs: number | null;
}

export interface ScanStatusResponse {
  scan: ScanSummary;
  repos: RepoProgress[];
}

/** A node position, either computed by layout or pinned by the user (FR-5.3). */
export interface NodePosition {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** A routed edge path produced by the layout engine. */
export interface EdgeRoute {
  id: string;
  points: Array<{ x: number; y: number }>;
}

export interface LayoutResult {
  positions: Record<string, NodePosition>;
  routes: Record<string, EdgeRoute>;
  width: number;
  height: number;
}

/** RFC 7807 problem detail, as returned by every backend error (§5). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
}

export const EDGE_TYPES: EdgeType[] = ['HTTP', 'ARTIFACT', 'MESSAGING', 'PERSISTENCE', 'UNKNOWN'];

export const CONFIDENCE_ORDER: Confidence[] = ['LOW', 'MEDIUM', 'HIGH'];

export function confidenceRank(confidence: Confidence): number {
  return CONFIDENCE_ORDER.indexOf(confidence);
}

/** Human labels for the signal sources shown in the inspector. */
export const SIGNAL_SOURCE_LABELS: Record<SignalSource, string> = {
  BUILD_DEPENDENCY: 'Build dependency',
  CONFIG_REFERENCE: 'Configuration',
  HTTP_CLIENT_CALL: 'HTTP client call',
  SOURCE_IMPORT: 'Source import',
  MESSAGING_PRODUCER: 'Produces to topic',
  MESSAGING_CONSUMER: 'Consumes from topic',
  DATASTORE_CONNECTION: 'Database connection',
  DATASTORE_SCHEMA: 'Owns the schema',
  DATASTORE_DRIVER: 'Database driver',
  PUBSUB_PUBLISHER: 'Publishes to Pub/Sub',
  PUBSUB_SUBSCRIBER: 'Subscribes on Pub/Sub',
  MANUAL: 'Added by you',
};
