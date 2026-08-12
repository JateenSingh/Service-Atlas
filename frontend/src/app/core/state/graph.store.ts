import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ServiceAtlasApi, problemMessage } from '../api/service-atlas-api.service';
import {
  Confidence,
  DependencyGraph,
  EdgeType,
  GraphEdge,
  GraphNode,
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
  search: string;
}

const DEFAULT_FILTERS: GraphFilters = {
  edgeTypes: new Set<EdgeType>(['HTTP', 'ARTIFACT', 'MESSAGING', 'UNKNOWN']),
  minConfidence: 'LOW',
  showExternal: true,
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

  readonly graph = this.graphSignal.asReadonly();
  readonly scanId = this.scanIdSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly filters = this.filtersSignal.asReadonly();
  readonly selection = this.selectionSignal.asReadonly();
  /** Double-click focus: this node and its direct neighbours stay lit, the rest fades (FR-5.3). */
  readonly focusKey = this.focusKeySignal.asReadonly();

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
      (node) => (filters.showExternal || node.type !== 'EXTERNAL') && matchesSearch(node),
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
  readonly hiddenNodeCount = computed(
    () => this.graphSignal().nodes.length - this.visibleGraph().nodes.length,
  );
  readonly hiddenEdgeCount = computed(
    () => this.graphSignal().edges.length - this.visibleGraph().edges.length,
  );

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
    try {
      const response = await firstValueFrom(this.api.getGraph(workspaceId));
      this.graphSignal.set(response.graph);
      this.scanIdSignal.set(response.scanId);
      this.clearSelection();
    } catch (problem) {
      this.errorSignal.set(problemMessage(problem));
      this.graphSignal.set({ nodes: [], edges: [] });
    } finally {
      this.loadingSignal.set(false);
    }
  }

  reset(): void {
    this.graphSignal.set({ nodes: [], edges: [] });
    this.scanIdSignal.set(null);
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
      filters.search.trim() !== ''
    );
  });
}
