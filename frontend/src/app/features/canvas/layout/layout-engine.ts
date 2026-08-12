import { LayoutResult } from '../../../core/models/graph.models';

export type LayoutDirection = 'RIGHT' | 'DOWN';

export interface LayoutNodeInput {
  key: string;
  width: number;
  height: number;
  /** Group membership, used to keep a repository's sub-modules near their parent (FR-2.4). */
  parentKey?: string;
}

export interface LayoutEdgeInput {
  id: string;
  sourceKey: string;
  targetKey: string;
}

export interface LayoutRequest {
  nodes: LayoutNodeInput[];
  edges: LayoutEdgeInput[];
  direction: LayoutDirection;
  /** Positions the user has pinned by dragging; applied after layout, never fought by it. */
  pinned?: Record<string, { x: number; y: number }>;
}

/**
 * The seam between the canvas and whatever computes coordinates (ADR-001).
 *
 * The canvas speaks only in Service Atlas terms — keys, sizes, a direction — and never sees an ELK
 * option string. Swapping the engine means writing one more implementation of this interface.
 */
export interface LayoutEngine {
  readonly id: string;

  layout(request: LayoutRequest): Promise<LayoutResult>;

  /** Releases any worker or other resource held by the engine. */
  dispose(): void;
}
