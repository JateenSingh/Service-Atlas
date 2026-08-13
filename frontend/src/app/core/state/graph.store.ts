import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ServiceAtlasApi, problemMessage } from '../api/service-atlas-api.service';
import {
  Confidence,
  DependencyGraph,
  EdgeType,
  GraphEdge,
  GraphNode,
  GraphResponse,
  NodeType,
  OverlayConflict,
  OverlayPatch,
  confidenceRank,
} from '../models/graph.models';

export type Selection =
  | { kind: 'node'; key: string }
  | { kind: 'edge'; id: string }
  | { kind: 'none' };

export interface GraphFilters {
  edgeTypes: Set<EdgeType>;
  minConfidence: Confidence;
  showExternal: boolean;
  showDatastores: boolean;
  search: string;
}

const DEFAULT_FILTERS: GraphFilters = {
  edgeTypes: new Set<EdgeType>(['HTTP', 'ARTIFACT', 'MESSAGING', 'PERSISTENCE', 'UNKNOWN']),
  minConfidence: 'LOW',
  showExternal: true,
  showDatastores: true,
  search: '',
};

/**
 * The graph, the filters applied to it, and what is selected (FR-4, FR-5.5, FR-5.8).
 *
 * Everything derived is a `computed`: the canvas, inspector, service list and export all read the
 * same `visibleGraph`, which is what makes "export what I see" (FR-6.3) true by construction
 * rather than by remembering to apply the same filters twice.
 */
@Injectable({ providedIn: 'root' })
export class GraphStore {
  private readonly api = inject(ServiceAtlasApi);

  private readonly graphSignal = signal<DependencyGraph>({ nodes: [], edges: [] });
  private readonly scanIdSignal = signal<number | null>(null);
  private readonly loadingSignal = signal(false);
  private readonly errorSignal = signal<string | null>(null);

  private readonly filtersSignal = signal<GraphFilters>({ ...DEFAULT_FILTERS });
  private readonly selectionSignal = signal<Selection>({ kind: 'none' });
  private readonly focusKeySignal = signal<string | null>(null);
  private readonly conflictsSignal = signal<OverlayConflict[]>([]);
  private readonly pinnedSignal = signal<Record<string, { x: number; y: number }>>({});
  private readonly workspaceIdSignal = signal<number | null>(null);
  private readonly hiddenNodeKeysSignal = signal<Set<string>>(new Set());
  private readonly hiddenEdgeIdsSignal = signal<Set<string>>(new Set());

  readonly graph = this.graphSignal.asReadonly();
  readonly scanId = this.scanIdSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly filters = this.filtersSignal.asReadonly();
  readonly selection = this.selectionSignal.asReadonly();
  /** Double-click focus: this node and its direct neighbours stay lit, the rest fades (FR-5.3). */
  readonly focusKey = this.focusKeySignal.asReadonly();
  /** Overlay/parser disagreements the user should see rather than have silently resolved (FR-4.4). */
  readonly conflicts = this.conflictsSignal.asReadonly();
  /** Positions the user has pinned by dragging, as last stored by the backend (FR-5.3). */
  readonly pinnedPositions = this.pinnedSignal.asReadonly();

  readonly isEmpty = computed(() => this.graphSignal().nodes.length === 0);

  /** The filtered view — the single source of truth for everything the user sees. */
  readonly visibleGraph = computed<DependencyGraph>(() => {
    const { nodes, edges } = this.graphSignal();
    const filters = this.filtersSignal();
    const search = filters.search.trim().toLowerCase();

    const matchesSearch = (node: GraphNode) =>
      search === '' ||
      node.displayName.toLowerCase().includes(search) ||
      node.key.toLowerCase().includes(search) ||
      (node.repoPath ?? '').toLowerCase().includes(search);

    const visibleNodes = nodes.filter(
      (node) =>
        (filters.showExternal || node.type !== 'EXTERNAL') &&
        (filters.showDatastores || node.type !== 'DATASTORE') &&
        matchesSearch(node),
    );
    const visibleKeys = new Set(visibleNodes.map((node) => node.key));

    const visibleEdges = edges.filter(
      (edge) =>
        filters.edgeTypes.has(edge.type) &&
        confidenceRank(edge.confidence) >= confidenceRank(filters.minConfidence) &&
        visibleKeys.has(edge.sourceKey) &&
        visibleKeys.has(edge.targetKey),
    );

    return { nodes: visibleNodes, edges: visibleEdges };
  });

  readonly visibleNodeCount = computed(() => this.visibleGraph().nodes.length);
  readonly visibleEdgeCount = computed(() => this.visibleGraph().edges.length);
  readonly hiddenNodeCount = computed(() => this.hiddenNodeKeysSignal().size);
  readonly hiddenEdgeCount = computed(() => this.hiddenEdgeIdsSignal().size);

  /** Nodes that are hidden but can be reshown (FR-4.4). */
  readonly hiddenNodes = computed(() => {
    const hiddenKeys = this.hiddenNodeKeysSignal();
    const nodesByKey = new Map(this.graphSignal().nodes.map((n) => [n.key, n]));
    return Array.from(hiddenKeys)
      .map((key) => nodesByKey.get(key))
      .filter((node): node is GraphNode => node !== undefined);
  });

  /** Edges that are hidden but can be reshown (FR-4.4). */
  readonly hiddenEdges = computed(() => {
    const hiddenIds = this.hiddenEdgeIdsSignal();
    const edgesById = new Map(this.graphSignal().edges.map((e) => [e.id, e]));
    return Array.from(hiddenIds)
      .map((id) => edgesById.get(id))
      .filter((edge): edge is GraphEdge => edge !== undefined);
  });

  readonly selectedNode = computed<GraphNode | null>(() => {
    const selection = this.selectionSignal();
    if (selection.kind !== 'node') {
      return null;
    }
    return this.graphSignal().nodes.find((node) => node.key === selection.key) ?? null;
  });

  readonly selectedEdge = computed<GraphEdge | null>(() => {
    const selection = this.selectionSignal();
    if (selection.kind !== 'edge') {
      return null;
    }
    return this.graphSignal().edges.find((edge) => edge.id === selection.id) ?? null;
  });

  /** Edges touching the selected node, for the inspector's dependency lists. */
  readonly selectedNodeEdges = computed(() => {
    const node = this.selectedNode();
    if (!node) {
      return { outgoing: [] as GraphEdge[], incoming: [] as GraphEdge[] };
    }
    const edges = this.graphSignal().edges;
    return {
      outgoing: edges.filter((edge) => edge.sourceKey === node.key),
      incoming: edges.filter((edge) => edge.targetKey === node.key),
    };
  });

  /** Node keys lit by focus mode: the focused node plus everything one hop away (FR-5.3). */
  readonly focusNeighbourhood = computed<Set<string> | null>(() => {
    const key = this.focusKeySignal();
    if (!key) {
      return null;
    }
    const neighbourhood = new Set<string>([key]);
    for (const edge of this.visibleGraph().edges) {
      if (edge.sourceKey === key) {
        neighbourhood.add(edge.targetKey);
      }
      if (edge.targetKey === key) {
        neighbourhood.add(edge.sourceKey);
      }
    }
    return neighbourhood;
  });

  readonly nodesByKey = computed(() => {
    const map = new Map<string, GraphNode>();
    for (const node of this.graphSignal().nodes) {
      map.set(node.key, node);
    }
    return map;
  });

  async load(workspaceId: number): Promise<void> {
    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    this.workspaceIdSignal.set(workspaceId);
    try {
      const response = await firstValueFrom(this.api.getGraph(workspaceId));
      console.debug('API response received:', response);
      this.accept(response);
      this.clearSelection();
    } catch (problem) {
      console.error('Error loading graph:', problem);
      this.errorSignal.set(problemMessage(problem));
      this.graphSignal.set({ nodes: [], edges: [] });
    } finally {
      this.loadingSignal.set(false);
    }
  }

  /** Adopts a server response — the one place graph, positions and conflicts are set together. */
  private accept(response: GraphResponse): void {
    this.graphSignal.set(response.graph);
    this.scanIdSignal.set(response.scanId);
    this.pinnedSignal.set(response.positions ?? {});
    this.conflictsSignal.set(response.conflicts ?? []);
    this.hiddenNodeKeysSignal.set(new Set(response.hiddenNodes ?? []));
    this.hiddenEdgeIdsSignal.set(new Set(response.hiddenEdges ?? []));
    console.debug('GraphStore.accept: hidden nodes =', response.hiddenNodes?.length ?? 0, 'hidden edges =', response.hiddenEdges?.length ?? 0);
  }

  /**
   * Sends an overlay patch and adopts the merged graph the server returns (FR-4.4).
   *
   * <p>The server's merge is authoritative rather than optimistic: a manual edge can turn out to
   * duplicate one the scanner already found, and only the backend knows that.
   */
  private async applyPatch(patch: OverlayPatch): Promise<void> {
    const workspaceId = this.workspaceIdSignal();
    if (workspaceId === null) {
      return;
    }
    this.errorSignal.set(null);
    try {
      const response = await firstValueFrom(this.api.patchOverlay(workspaceId, patch));
      console.debug('Patch response received:', response);
      this.accept(response);
    } catch (problem) {
      console.error('Error applying patch:', problem);
      this.errorSignal.set(problemMessage(problem));
    }
  }

  /** FR-5.3 — persists a dragged position so it survives a reload and a re-scan. */
  async pinPosition(key: string, x: number, y: number): Promise<void> {
    await this.applyPatch({ positions: { [key]: { x, y } } });
  }

  async hideNode(key: string): Promise<void> {
    await this.applyPatch({ hiddenNodes: [key] });
    this.clearSelection();
  }

  async unhideNode(key: string): Promise<void> {
    await this.applyPatch({ removeHiddenNodes: [key] });
  }

  async hideEdge(id: string): Promise<void> {
    await this.applyPatch({ hiddenEdges: [id] });
    this.clearSelection();
  }

  async unhideEdge(id: string): Promise<void> {
    await this.applyPatch({ removeHiddenEdges: [id] });
  }

  async addEdge(sourceKey: string, targetKey: string, type: EdgeType, label?: string): Promise<void> {
    await this.applyPatch({ addedEdgeRequests: [{ sourceKey, targetKey, type, label }] });
  }

  async addNode(key: string, displayName: string, type: NodeType, note?: string): Promise<void> {
    await this.applyPatch({ addedNodes: [{ key, displayName, type, note }] });
  }

  async annotateNode(key: string, note: string): Promise<void> {
    await this.applyPatch(
      note.trim() ? { nodeNotes: { [key]: note.trim() } } : { removeNodeNotes: [key] },
    );
  }

  /** Drops every manual edit and layout override for this workspace. */
  async clearOverlay(): Promise<void> {
    await this.applyPatch({ clear: true });
  }

  reset(): void {
    this.graphSignal.set({ nodes: [], edges: [] });
    this.scanIdSignal.set(null);
    this.conflictsSignal.set([]);
    this.pinnedSignal.set({});
    this.hiddenNodeKeysSignal.set(new Set());
    this.hiddenEdgeIdsSignal.set(new Set());
    this.clearSelection();
    this.filtersSignal.set({ ...DEFAULT_FILTERS, edgeTypes: new Set(DEFAULT_FILTERS.edgeTypes) });
  }

  selectNode(key: string): void {
    this.selectionSignal.set({ kind: 'node', key });
  }

  selectEdge(id: string): void {
    this.selectionSignal.set({ kind: 'edge', id });
  }

  clearSelection(): void {
    this.selectionSignal.set({ kind: 'none' });
    this.focusKeySignal.set(null);
  }

  toggleFocus(key: string): void {
    this.focusKeySignal.update((current) => (current === key ? null : key));
  }

  setSearch(search: string): void {
    this.filtersSignal.update((filters) => ({ ...filters, search }));
  }

  toggleEdgeType(type: EdgeType): void {
    this.filtersSignal.update((filters) => {
      const edgeTypes = new Set(filters.edgeTypes);
      if (edgeTypes.has(type)) {
        edgeTypes.delete(type);
      } else {
        edgeTypes.add(type);
      }
      return { ...filters, edgeTypes };
    });
  }

  setMinConfidence(minConfidence: Confidence): void {
    this.filtersSignal.update((filters) => ({ ...filters, minConfidence }));
  }

  setShowExternal(showExternal: boolean): void {
    this.filtersSignal.update((filters) => ({ ...filters, showExternal }));
  }

  /** Databases and caches are infrastructure; some readings of a diagram want them out of the way. */
  setShowDatastores(showDatastores: boolean): void {
    this.filtersSignal.update((filters) => ({ ...filters, showDatastores }));
  }

  resetFilters(): void {
    this.filtersSignal.set({ ...DEFAULT_FILTERS, edgeTypes: new Set(DEFAULT_FILTERS.edgeTypes) });
  }

  /** True when any filter is narrowing the view — drives the "filters active" affordance. */
  readonly filtersActive = computed(() => {
    const filters = this.filtersSignal();
    return (
      filters.edgeTypes.size !== DEFAULT_FILTERS.edgeTypes.size ||
      filters.minConfidence !== DEFAULT_FILTERS.minConfidence ||
      filters.showExternal !== DEFAULT_FILTERS.showExternal ||
      filters.showDatastores !== DEFAULT_FILTERS.showDatastores ||
      filters.search.trim() !== ''
    );
  });
}
