import { Injectable, computed, inject, signal } from '@angular/core';
import { DependencyGraph, GraphNode, LayoutResult, NodePosition } from '../models/graph.models';
import { ElkLayoutEngine } from '../../features/canvas/layout/elk-layout.engine';
import { LayoutDirection, LayoutRequest } from '../../features/canvas/layout/layout-engine';

export const MIN_ZOOM = 0.1;
export const MAX_ZOOM = 4;

export interface Viewport {
  x: number;
  y: number;
  zoom: number;
}

/** Node box sizes, chosen so a typical service name fits without truncation. */
const NODE_HEIGHT = 64;
const TOPIC_HEIGHT = 44;
const MIN_NODE_WIDTH = 168;
const MAX_NODE_WIDTH = 280;

/**
 * Layout results and the viewport (FR-5.1, FR-5.2).
 *
 * Layout is asynchronous, so the canvas has three states, not two: no graph, graph-without-layout,
 * and laid out. `layoutPending` exists so the canvas can show a skeleton instead of stacking every
 * node at the origin while ELK works.
 */
@Injectable({ providedIn: 'root' })
export class LayoutStore {
  private readonly engine = inject(ElkLayoutEngine);

  private readonly layoutSignal = signal<LayoutResult>({
    positions: {},
    routes: {},
    width: 0,
    height: 0,
  });
  private readonly directionSignal = signal<LayoutDirection>('RIGHT');
  private readonly pendingSignal = signal(false);
  private readonly errorSignal = signal<string | null>(null);
  private readonly viewportSignal = signal<Viewport>({ x: 0, y: 0, zoom: 1 });

  /** Positions the user has dragged (FR-5.3); persisted as overlays in the workspace. */
  private readonly pinnedSignal = signal<Record<string, { x: number; y: number }>>({});

  readonly layout = this.layoutSignal.asReadonly();
  readonly direction = this.directionSignal.asReadonly();
  readonly pending = this.pendingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly viewport = this.viewportSignal.asReadonly();
  readonly pinned = this.pinnedSignal.asReadonly();

  readonly hasLayout = computed(() => Object.keys(this.layoutSignal().positions).length > 0);

  readonly transform = computed(() => {
    const { x, y, zoom } = this.viewportSignal();
    return `translate(${x}, ${y}) scale(${zoom})`;
  });

  readonly zoomPercent = computed(() => Math.round(this.viewportSignal().zoom * 100));

  async relayout(graph: DependencyGraph): Promise<void> {
    if (graph.nodes.length === 0) {
      this.layoutSignal.set({ positions: {}, routes: {}, width: 0, height: 0 });
      return;
    }
    this.pendingSignal.set(true);
    this.errorSignal.set(null);

    const request: LayoutRequest = {
      direction: this.directionSignal(),
      pinned: this.pinnedSignal(),
      nodes: graph.nodes.map((node) => ({
        key: node.key,
        width: nodeWidth(node),
        height: nodeHeight(node),
        parentKey: node.parentKey,
      })),
      edges: graph.edges.map((edge) => ({
        id: edge.id,
        sourceKey: edge.sourceKey,
        targetKey: edge.targetKey,
      })),
    };

    try {
      this.layoutSignal.set(await this.engine.layout(request));
    } catch (error) {
      this.errorSignal.set(
        error instanceof Error ? error.message : 'Layout failed for an unknown reason.',
      );
    } finally {
      this.pendingSignal.set(false);
    }
  }

  setDirection(direction: LayoutDirection): void {
    this.directionSignal.set(direction);
  }

  /** Records a dragged position and moves the node immediately, without waiting for a re-layout. */
  pin(key: string, x: number, y: number): void {
    this.pinnedSignal.update((pinned) => ({ ...pinned, [key]: { x, y } }));
    this.layoutSignal.update((layout) => {
      const existing = layout.positions[key];
      if (!existing) {
        return layout;
      }
      return { ...layout, positions: { ...layout.positions, [key]: { ...existing, x, y } } };
    });
  }

  setPinned(pinned: Record<string, { x: number; y: number }>): void {
    this.pinnedSignal.set(pinned);
  }

  clearPinned(): void {
    this.pinnedSignal.set({});
  }

  position(key: string): NodePosition | undefined {
    return this.layoutSignal().positions[key];
  }

  // ---------------------------------------------------------------- viewport

  panBy(dx: number, dy: number): void {
    this.viewportSignal.update((viewport) => ({
      ...viewport,
      x: viewport.x + dx,
      y: viewport.y + dy,
    }));
  }

  /** Zooms around a fixed screen point, so the content under the cursor stays under the cursor. */
  zoomAt(screenX: number, screenY: number, factor: number): void {
    this.viewportSignal.update((viewport) => {
      const zoom = clamp(viewport.zoom * factor, MIN_ZOOM, MAX_ZOOM);
      const applied = zoom / viewport.zoom;
      return {
        zoom,
        x: screenX - (screenX - viewport.x) * applied,
        y: screenY - (screenY - viewport.y) * applied,
      };
    });
  }

  setZoom(zoom: number, centreX: number, centreY: number): void {
    const current = this.viewportSignal().zoom;
    this.zoomAt(centreX, centreY, clamp(zoom, MIN_ZOOM, MAX_ZOOM) / current);
  }

  /** FR-5.1 — fit the whole graph in the viewport, with a margin so nothing touches the edge. */
  fit(viewWidth: number, viewHeight: number): void {
    const layout = this.layoutSignal();
    const bounds = contentBounds(layout);
    if (!bounds || viewWidth <= 0 || viewHeight <= 0) {
      return;
    }
    const margin = 48;
    const zoom = clamp(
      Math.min(
        (viewWidth - margin * 2) / Math.max(bounds.width, 1),
        (viewHeight - margin * 2) / Math.max(bounds.height, 1),
      ),
      MIN_ZOOM,
      MAX_ZOOM,
    );
    this.viewportSignal.set({
      zoom,
      x: (viewWidth - bounds.width * zoom) / 2 - bounds.x * zoom,
      y: (viewHeight - bounds.height * zoom) / 2 - bounds.y * zoom,
    });
  }

  /** Centres one node without changing zoom — used by the service list and by search. */
  centreOn(key: string, viewWidth: number, viewHeight: number): void {
    const position = this.position(key);
    if (!position) {
      return;
    }
    const { zoom } = this.viewportSignal();
    this.viewportSignal.set({
      zoom,
      x: viewWidth / 2 - (position.x + position.width / 2) * zoom,
      y: viewHeight / 2 - (position.y + position.height / 2) * zoom,
    });
  }

  resetViewport(): void {
    this.viewportSignal.set({ x: 0, y: 0, zoom: 1 });
  }

  /** Converts a screen point to graph coordinates — needed while dragging a node. */
  toGraphPoint(screenX: number, screenY: number): { x: number; y: number } {
    const { x, y, zoom } = this.viewportSignal();
    return { x: (screenX - x) / zoom, y: (screenY - y) / zoom };
  }
}

export function nodeWidth(node: GraphNode): number {
  const approximate = 44 + node.displayName.length * 8.2;
  return Math.round(clamp(approximate, MIN_NODE_WIDTH, MAX_NODE_WIDTH));
}

export function nodeHeight(node: GraphNode): number {
  return node.type === 'TOPIC' ? TOPIC_HEIGHT : NODE_HEIGHT;
}

export function contentBounds(
  layout: LayoutResult,
): { x: number; y: number; width: number; height: number } | null {
  const positions = Object.values(layout.positions);
  if (positions.length === 0) {
    return null;
  }
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const position of positions) {
    minX = Math.min(minX, position.x);
    minY = Math.min(minY, position.y);
    maxX = Math.max(maxX, position.x + position.width);
    maxY = Math.max(maxY, position.y + position.height);
  }
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}
