import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { GraphStore } from '../../core/state/graph.store';
import { LayoutStore, contentBounds } from '../../core/state/layout.store';
import { EdgeType, GraphEdge, GraphNode, NodeType } from '../../core/models/graph.models';
import { MinimapComponent } from './minimap.component';

interface RenderedNode {
  node: GraphNode;
  x: number;
  y: number;
  width: number;
  height: number;
  colour: string;
  dimmed: boolean;
  selected: boolean;
  focused: boolean;
}

interface RenderedEdge {
  edge: GraphEdge;
  path: string;
  labelX: number;
  labelY: number;
  colour: string;
  dash: string | null;
  opacity: number;
  dimmed: boolean;
  selected: boolean;
}

/**
 * The custom SVG canvas (§2: no third-party diagram widgets; FR-5.1 … FR-5.7).
 *
 * <p>Rendering is a pure projection of two signals — the filtered graph and the layout — through
 * `computed`. There is no imperative redraw path: change a filter or drag a node and the template
 * re-renders itself (FR-5.8).
 *
 * <p>Pan and zoom are a single transform on the root group rather than per-element maths, which
 * keeps interaction smooth at the 100-node / 400-edge target (FR-5.7) and makes SVG export a
 * matter of serialising what is already there.
 */
@Component({
  selector: 'sa-graph-canvas',
  standalone: true,
  imports: [MinimapComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './graph-canvas.component.css',
  templateUrl: './graph-canvas.component.html',
})
export class GraphCanvasComponent {
  private readonly graphStore = inject(GraphStore);
  readonly layoutStore = inject(LayoutStore);

  private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
  readonly svgRef = viewChild.required<ElementRef<SVGSVGElement>>('svg');

  private readonly viewSize = signal({ width: 0, height: 0 });
  private readonly hoveredEdgeId = signal<string | null>(null);
  private readonly draggingKey = signal<string | null>(null);

  private panPointerId: number | null = null;
  private panOrigin = { x: 0, y: 0 };
  private dragOffset = { x: 0, y: 0 };
  private dragMoved = false;

  readonly transform = this.layoutStore.transform;
  readonly pending = this.layoutStore.pending;
  readonly hoveredEdge = computed(() => {
    const id = this.hoveredEdgeId();
    return id === null ? null : (this.renderedEdges().find((edge) => edge.edge.id === id) ?? null);
  });

  readonly isEmpty = computed(() => this.graphStore.visibleGraph().nodes.length === 0);

  readonly renderedNodes = computed<RenderedNode[]>(() => {
    const layout = this.layoutStore.layout();
    const selection = this.graphStore.selection();
    const neighbourhood = this.graphStore.focusNeighbourhood();
    const focusKey = this.graphStore.focusKey();

    return this.graphStore
      .visibleGraph()
      .nodes.map((node) => {
        const position = layout.positions[node.key];
        if (!position) {
          return null;
        }
        return {
          node,
          x: position.x,
          y: position.y,
          width: position.width,
          height: position.height,
          colour: nodeColour(node),
          dimmed: neighbourhood !== null && !neighbourhood.has(node.key),
          selected: selection.kind === 'node' && selection.key === node.key,
          focused: focusKey === node.key,
        } satisfies RenderedNode;
      })
      .filter((node): node is RenderedNode => node !== null);
  });

  readonly renderedEdges = computed<RenderedEdge[]>(() => {
    const layout = this.layoutStore.layout();
    const selection = this.graphStore.selection();
    const neighbourhood = this.graphStore.focusNeighbourhood();

    return this.graphStore
      .visibleGraph()
      .edges.map((edge) => {
        const route = layout.routes[edge.id];
        const source = layout.positions[edge.sourceKey];
        const target = layout.positions[edge.targetKey];
        if (!source || !target) {
          return null;
        }

        // ELK's routed points when it has them; a straight centre-to-centre line otherwise, which
        // happens for edges added after the last layout ran.
        const points = route?.points ?? [
          { x: source.x + source.width, y: source.y + source.height / 2 },
          { x: target.x, y: target.y + target.height / 2 },
        ];
        const middle = points[Math.floor(points.length / 2)];

        return {
          edge,
          path: toPath(points),
          labelX: middle.x,
          labelY: middle.y - 6,
          colour: edgeColour(edge.type),
          dash: edgeDash(edge.type),
          opacity: confidenceOpacity(edge.confidence),
          dimmed:
            neighbourhood !== null &&
            !(neighbourhood.has(edge.sourceKey) && neighbourhood.has(edge.targetKey)),
          selected: selection.kind === 'edge' && selection.id === edge.id,
        } satisfies RenderedEdge;
      })
      .filter((edge): edge is RenderedEdge => edge !== null);
  });

  constructor() {
    // Re-layout whenever the visible graph or the direction changes (FR-5.8).
    effect(() => {
      const graph = this.graphStore.visibleGraph();
      this.layoutStore.direction();
      void this.layoutStore.relayout(graph).then(() => this.fitIfUnviewed());
    });

    effect((onCleanup) => {
      const element = this.host().nativeElement;
      const observer = new ResizeObserver(([entry]) => {
        this.viewSize.set({
          width: entry.contentRect.width,
          height: entry.contentRect.height,
        });
      });
      observer.observe(element);
      onCleanup(() => observer.disconnect());
    });
  }

  private hasFittedFor = '';

  /** Fits once per distinct graph, so a re-render does not yank a viewport the user has moved. */
  private fitIfUnviewed(): void {
    const layout = this.layoutStore.layout();
    const bounds = contentBounds(layout);
    if (!bounds) {
      return;
    }
    const signature = `${Object.keys(layout.positions).length}:${Math.round(bounds.width)}x${Math.round(bounds.height)}`;
    if (signature === this.hasFittedFor) {
      return;
    }
    this.hasFittedFor = signature;
    this.fitToScreen();
  }

  fitToScreen(): void {
    const { width, height } = this.viewSize();
    this.layoutStore.fit(width, height);
  }

  centreOnNode(key: string): void {
    const { width, height } = this.viewSize();
    this.layoutStore.centreOn(key, width, height);
  }

  // ---------------------------------------------------------------- interaction

  onWheel(event: WheelEvent): void {
    event.preventDefault();
    const rect = this.svgRef().nativeElement.getBoundingClientRect();
    // Exponential steps keep zoom feeling linear across the whole 10%–400% range.
    const factor = Math.exp(-event.deltaY * 0.0015);
    this.layoutStore.zoomAt(event.clientX - rect.left, event.clientY - rect.top, factor);
  }

  onPointerDown(event: PointerEvent): void {
    if (event.button !== 0 && event.button !== 1) {
      return;
    }
    const nodeKey = nodeKeyFromEvent(event);
    const rect = this.svgRef().nativeElement.getBoundingClientRect();
    const point = this.layoutStore.toGraphPoint(event.clientX - rect.left, event.clientY - rect.top);

    if (nodeKey && event.button === 0) {
      const position = this.layoutStore.position(nodeKey);
      if (position) {
        this.draggingKey.set(nodeKey);
        this.dragOffset = { x: point.x - position.x, y: point.y - position.y };
        this.dragMoved = false;
      }
    } else {
      this.panPointerId = event.pointerId;
      this.panOrigin = { x: event.clientX, y: event.clientY };
    }
    (event.target as Element).setPointerCapture?.(event.pointerId);
  }

  onPointerMove(event: PointerEvent): void {
    const draggingKey = this.draggingKey();
    if (draggingKey) {
      const rect = this.svgRef().nativeElement.getBoundingClientRect();
      const point = this.layoutStore.toGraphPoint(
        event.clientX - rect.left,
        event.clientY - rect.top,
      );
      this.layoutStore.pin(draggingKey, point.x - this.dragOffset.x, point.y - this.dragOffset.y);
      this.dragMoved = true;
      return;
    }
    if (this.panPointerId === event.pointerId) {
      this.layoutStore.panBy(event.clientX - this.panOrigin.x, event.clientY - this.panOrigin.y);
      this.panOrigin = { x: event.clientX, y: event.clientY };
    }
  }

  onPointerUp(event: PointerEvent): void {
    const draggedKey = this.draggingKey();
    if (draggedKey && this.dragMoved) {
      this.nodeMoved.emit(draggedKey);
    }
    this.draggingKey.set(null);
    this.panPointerId = null;
    (event.target as Element).releasePointerCapture?.(event.pointerId);
  }

  /** Emitted after a drag settles, so the position can be persisted as an overlay (FR-4.4). */
  readonly nodeMoved = output<string>();

  onNodeClick(node: GraphNode, event: Event): void {
    event.stopPropagation();
    if (this.dragMoved) {
      this.dragMoved = false;
      return; // a drag is not a click
    }
    this.graphStore.selectNode(node.key);
  }

  onNodeDoubleClick(node: GraphNode, event: Event): void {
    event.stopPropagation();
    this.graphStore.toggleFocus(node.key);
  }

  onEdgeClick(edge: GraphEdge, event: Event): void {
    event.stopPropagation();
    this.graphStore.selectEdge(edge.id);
  }

  onEdgeEnter(edge: GraphEdge): void {
    this.hoveredEdgeId.set(edge.id);
  }

  onEdgeLeave(): void {
    this.hoveredEdgeId.set(null);
  }

  onBackgroundClick(): void {
    this.graphStore.clearSelection();
  }

  zoomIn(): void {
    const { width, height } = this.viewSize();
    this.layoutStore.zoomAt(width / 2, height / 2, 1.2);
  }

  zoomOut(): void {
    const { width, height } = this.viewSize();
    this.layoutStore.zoomAt(width / 2, height / 2, 1 / 1.2);
  }

  trackNode = (_: number, rendered: RenderedNode) => rendered.node.key;
  trackEdge = (_: number, rendered: RenderedEdge) => rendered.edge.id;

  nodeSubtitle(node: GraphNode): string {
    switch (node.type) {
      case 'TOPIC':
        return 'topic';
      case 'EXTERNAL':
        return 'external';
      case 'SUB_MODULE':
        return 'module';
      default:
        return node.framework && node.framework !== 'Unknown' ? node.framework : 'service';
    }
  }
}

function toPath(points: Array<{ x: number; y: number }>): string {
  return points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${round(point.x)} ${round(point.y)}`)
    .join(' ');
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}

/** FR-5.6 — node colour by framework, with distinct treatments for topics and external nodes. */
export function nodeColour(node: GraphNode): string {
  if (node.type === 'TOPIC') {
    return 'var(--fw-topic)';
  }
  if (node.type === 'EXTERNAL') {
    return 'var(--fw-external)';
  }
  switch (node.framework) {
    case 'Play Framework':
      return 'var(--fw-play)';
    case 'Akka HTTP':
      return 'var(--fw-akka)';
    case 'Pekko HTTP':
      return 'var(--fw-pekko)';
    case 'http4s':
      return 'var(--fw-http4s)';
    case 'gRPC':
      return 'var(--fw-grpc)';
    default:
      return 'var(--fw-unknown)';
  }
}

/** FR-5.6 — edge style by type: solid HTTP, dashed messaging, dotted artifact. */
export function edgeDash(type: EdgeType): string | null {
  switch (type) {
    case 'MESSAGING':
      return '8 5';
    case 'ARTIFACT':
      return '2 4';
    case 'UNKNOWN':
      return '4 4';
    default:
      return null;
  }
}

export function edgeColour(type: EdgeType): string {
  switch (type) {
    case 'HTTP':
      return 'var(--edge-http)';
    case 'ARTIFACT':
      return 'var(--edge-artifact)';
    case 'MESSAGING':
      return 'var(--edge-messaging)';
    default:
      return 'var(--edge-unknown)';
  }
}

/** FR-5.6 — opacity carries confidence, so a weak edge looks weak. */
export function confidenceOpacity(confidence: string): number {
  switch (confidence) {
    case 'HIGH':
      return 0.95;
    case 'MEDIUM':
      return 0.66;
    default:
      return 0.4;
  }
}

export function nodeTypeLabel(type: NodeType): string {
  switch (type) {
    case 'SUB_MODULE':
      return 'Module';
    case 'TOPIC':
      return 'Topic';
    case 'EXTERNAL':
      return 'External';
    default:
      return 'Service';
  }
}

function nodeKeyFromEvent(event: PointerEvent): string | null {
  const target = event.target as Element | null;
  const group = target?.closest('[data-node-key]');
  return group?.getAttribute('data-node-key') ?? null;
}
