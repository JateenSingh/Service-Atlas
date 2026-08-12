import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { GraphStore } from './graph.store';
import { DependencyGraph, GraphResponse } from '../models/graph.models';
import { ServiceAtlasApi } from '../api/service-atlas-api.service';
import { of } from 'rxjs';

/**
 * FR-5.5 / FR-6.3 — the filtered view.
 *
 * These are the derivations everything else reads: the canvas, the service list and every export
 * all render `visibleGraph`, so if the filtering is wrong, the exported file is wrong too.
 */
describe('GraphStore', () => {
  const graph: DependencyGraph = {
    nodes: [
      {
        key: 'order',
        displayName: 'log-order-svc',
        type: 'SERVICE',
        framework: 'Play Framework',
        endpoints: [],
        warnings: [],
        metadata: {},
      },
      {
        key: 'quote',
        displayName: 'log-quote-svc',
        type: 'SERVICE',
        endpoints: [],
        warnings: [],
        metadata: {},
      },
      {
        key: 'external:stripe',
        displayName: 'api.stripe.com',
        type: 'EXTERNAL',
        endpoints: [],
        warnings: [],
        metadata: {},
      },
    ],
    edges: [
      {
        id: 'order->quote:HTTP',
        sourceKey: 'order',
        targetKey: 'quote',
        type: 'HTTP',
        confidence: 'HIGH',
        evidence: [],
      },
      {
        id: 'order->quote:ARTIFACT',
        sourceKey: 'order',
        targetKey: 'quote',
        type: 'ARTIFACT',
        confidence: 'LOW',
        evidence: [],
      },
      {
        id: 'order->external:stripe:HTTP',
        sourceKey: 'order',
        targetKey: 'external:stripe',
        type: 'HTTP',
        confidence: 'MEDIUM',
        evidence: [],
      },
    ],
  };

  const response: GraphResponse = {
    scanId: 7,
    nodeCount: 3,
    edgeCount: 3,
    graph,
    positions: { order: { x: 10, y: 20 } },
    conflicts: [{ kind: 'HIDDEN_EDGE_REAPPEARED', target: 'x', message: 'came back' }],
  };

  let store: GraphStore;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ServiceAtlasApi,
          useValue: { getGraph: () => of(response), patchOverlay: () => of(response) },
        },
      ],
    });
    store = TestBed.inject(GraphStore);
    await store.load(1);
  });

  it('loads the graph, positions and conflicts together', () => {
    expect(store.scanId()).toBe(7);
    expect(store.graph().nodes.length).toBe(3);
    expect(store.pinnedPositions()['order']).toEqual({ x: 10, y: 20 });
    expect(store.conflicts().length).toBe(1);
  });

  it('shows everything by default', () => {
    expect(store.visibleNodeCount()).toBe(3);
    expect(store.visibleEdgeCount()).toBe(3);
    expect(store.filtersActive()).toBeFalse();
  });

  it('filters by edge type', () => {
    store.toggleEdgeType('ARTIFACT');

    expect(store.visibleGraph().edges.map((edge) => edge.type)).toEqual(['HTTP', 'HTTP']);
    expect(store.filtersActive()).toBeTrue();
  });

  it('filters by confidence threshold', () => {
    store.setMinConfidence('HIGH');

    expect(store.visibleGraph().edges.map((edge) => edge.id)).toEqual(['order->quote:HTTP']);
  });

  it('hides external services and the edges that reach them', () => {
    store.setShowExternal(false);

    expect(store.visibleGraph().nodes.map((node) => node.key)).toEqual(['order', 'quote']);
    expect(store.visibleGraph().edges.every((edge) => !edge.targetKey.startsWith('external:')))
      .toBeTrue();
  });

  it('searches by name, key and repository path', () => {
    store.setSearch('quote');

    expect(store.visibleGraph().nodes.map((node) => node.key)).toEqual(['quote']);
    // An edge whose other end is filtered out must not dangle.
    expect(store.visibleGraph().edges).toEqual([]);
  });

  it('never leaves an edge without both of its nodes', () => {
    store.setSearch('order');

    const keys = new Set(store.visibleGraph().nodes.map((node) => node.key));
    for (const edge of store.visibleGraph().edges) {
      expect(keys.has(edge.sourceKey)).toBeTrue();
      expect(keys.has(edge.targetKey)).toBeTrue();
    }
  });

  it('reports how much the filters are hiding', () => {
    store.setMinConfidence('HIGH');

    expect(store.hiddenEdgeCount()).toBe(2);
    expect(store.hiddenNodeCount()).toBe(0);
  });

  it('resets filters back to showing everything', () => {
    store.toggleEdgeType('HTTP');
    store.setSearch('order');
    store.resetFilters();

    expect(store.filtersActive()).toBeFalse();
    expect(store.visibleEdgeCount()).toBe(3);
  });

  it('exposes the selected node with its incoming and outgoing edges', () => {
    store.selectNode('quote');

    expect(store.selectedNode()?.displayName).toBe('log-quote-svc');
    expect(store.selectedNodeEdges().incoming.length).toBe(2);
    expect(store.selectedNodeEdges().outgoing.length).toBe(0);
  });

  it('focus mode lights the node and its direct neighbours only', () => {
    store.toggleFocus('quote');

    expect([...store.focusNeighbourhood()!].sort()).toEqual(['order', 'quote']);

    store.toggleFocus('quote');
    expect(store.focusNeighbourhood()).toBeNull();
  });

  it('clearing the selection also clears focus', () => {
    store.selectNode('order');
    store.toggleFocus('order');
    store.clearSelection();

    expect(store.selection().kind).toBe('none');
    expect(store.focusKey()).toBeNull();
  });
});

/**
 * FR-3.9 / FR-3.10 — datastores and Pub/Sub are first-class on the canvas, which means they have to
 * be first-class in the filtering too: a persistence edge must be visible by default and must
 * disappear cleanly, taking its now-orphaned datastore with it.
 */
describe('GraphStore with datastores and topics', () => {
  const graph: DependencyGraph = {
    nodes: [
      { key: 'order', displayName: 'log-order-svc', type: 'SERVICE', endpoints: [], warnings: [], metadata: {} },
      { key: 'quote', displayName: 'log-quote-svc', type: 'SERVICE', endpoints: [], warnings: [], metadata: {} },
      {
        key: 'datastore:redis:pricingcache',
        displayName: 'pricing-cache',
        type: 'DATASTORE',
        endpoints: [],
        warnings: [],
        metadata: { engine: 'Redis' },
      },
      {
        key: 'topic:ordereventsv2',
        displayName: 'order-events-v2',
        type: 'TOPIC',
        endpoints: [],
        warnings: [],
        metadata: { broker: 'Google Pub/Sub' },
      },
    ],
    edges: [
      {
        id: 'order->cache:PERSISTENCE',
        sourceKey: 'order',
        targetKey: 'datastore:redis:pricingcache',
        type: 'PERSISTENCE',
        confidence: 'HIGH',
        evidence: [],
      },
      {
        id: 'quote->cache:PERSISTENCE',
        sourceKey: 'quote',
        targetKey: 'datastore:redis:pricingcache',
        type: 'PERSISTENCE',
        confidence: 'HIGH',
        evidence: [],
      },
      {
        id: 'order->topic:MESSAGING',
        sourceKey: 'order',
        targetKey: 'topic:ordereventsv2',
        type: 'MESSAGING',
        confidence: 'MEDIUM',
        evidence: [],
      },
    ],
  };

  const response: GraphResponse = {
    scanId: 8,
    nodeCount: 4,
    edgeCount: 3,
    graph,
    positions: {},
    conflicts: [],
  };

  let store: GraphStore;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ServiceAtlasApi,
          useValue: { getGraph: () => of(response), patchOverlay: () => of(response) },
        },
      ],
    });
    store = TestBed.inject(GraphStore);
    await store.load(1);
  });

  it('shows persistence edges without being asked', () => {
    expect(store.visibleEdgeCount()).toBe(3);
    expect(store.filtersActive()).toBeFalse();
  });

  it('turning persistence off hides the storage edges and nothing else', () => {
    store.toggleEdgeType('PERSISTENCE');

    expect(store.visibleGraph().edges.map((edge) => edge.type)).toEqual(['MESSAGING']);
    // The datastore itself stays: edge filters filter edges. A node only disappears through the
    // search box or the external toggle, which is what makes "hidden nodes" a separate count.
    expect(store.visibleNodeCount()).toBe(4);
    expect(store.hiddenNodeCount()).toBe(0);
  });

  it('a shared datastore reports both of the services that reach it', () => {
    store.selectNode('datastore:redis:pricingcache');

    expect(store.selectedNodeEdges().incoming.map((edge) => edge.sourceKey).sort()).toEqual([
      'order',
      'quote',
    ]);
    expect(store.selectedNodeEdges().outgoing).toEqual([]);
  });

  it('hides datastores on request, and the persistence edges with them', () => {
    store.setShowDatastores(false);

    expect(store.visibleGraph().nodes.map((node) => node.key)).not.toContain(
      'datastore:redis:pricingcache',
    );
    expect(store.visibleGraph().edges.map((edge) => edge.type)).toEqual(['MESSAGING']);
    expect(store.filtersActive()).toBeTrue();

    store.resetFilters();
    expect(store.visibleNodeCount()).toBe(4);
  });

  it('a datastore is searchable by name like anything else', () => {
    store.setSearch('pricing');

    expect(store.visibleGraph().nodes.map((node) => node.key)).toEqual([
      'datastore:redis:pricingcache',
    ]);
  });
});
