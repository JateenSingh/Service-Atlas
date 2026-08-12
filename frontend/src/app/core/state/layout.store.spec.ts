import { TestBed } from '@angular/core/testing';
import { LayoutStore, MAX_ZOOM, MIN_ZOOM, contentBounds, nodeHeight, nodeWidth } from './layout.store';
import { GraphNode, LayoutResult } from '../models/graph.models';
import { ElkLayoutEngine } from '../../features/canvas/layout/elk-layout.engine';

/** FR-5.1 — pan, zoom and fit-to-screen arithmetic. */
describe('LayoutStore', () => {
  let store: LayoutStore;

  const layout: LayoutResult = {
    positions: {
      a: { x: 0, y: 0, width: 200, height: 64 },
      b: { x: 300, y: 200, width: 200, height: 64 },
    },
    routes: {},
    width: 500,
    height: 264,
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ElkLayoutEngine,
          useValue: { id: 'stub', layout: () => Promise.resolve(layout), dispose: () => {} },
        },
      ],
    });
    store = TestBed.inject(LayoutStore);
    await store.relayout({
      nodes: [
        { key: 'a', displayName: 'a', type: 'SERVICE', endpoints: [], warnings: [], metadata: {} },
        { key: 'b', displayName: 'b', type: 'SERVICE', endpoints: [], warnings: [], metadata: {} },
      ],
      edges: [],
    });
  });

  it('starts unzoomed and untranslated', () => {
    expect(store.viewport()).toEqual({ x: 0, y: 0, zoom: 1 });
    expect(store.zoomPercent()).toBe(100);
  });

  it('pans by a delta', () => {
    store.panBy(30, -10);
    store.panBy(5, 5);

    expect(store.viewport().x).toBe(35);
    expect(store.viewport().y).toBe(-5);
  });

  it('keeps the point under the cursor fixed while zooming', () => {
    // The graph point under screen (100, 100) before and after must be the same.
    const before = store.toGraphPoint(100, 100);
    store.zoomAt(100, 100, 2);
    const after = store.toGraphPoint(100, 100);

    expect(after.x).toBeCloseTo(before.x, 6);
    expect(after.y).toBeCloseTo(before.y, 6);
    expect(store.viewport().zoom).toBe(2);
  });

  it('clamps zoom to the 10%–400% range (FR-5.1)', () => {
    store.zoomAt(0, 0, 100);
    expect(store.viewport().zoom).toBe(MAX_ZOOM);

    store.zoomAt(0, 0, 0.0001);
    expect(store.viewport().zoom).toBe(MIN_ZOOM);
  });

  it('fits the whole graph into the viewport with a margin', () => {
    store.fit(1000, 600);

    const { zoom, x, y } = store.viewport();
    expect(zoom).toBeGreaterThan(0);
    // Every node must land inside the viewport once the transform is applied.
    for (const position of Object.values(store.layout().positions)) {
      expect(position.x * zoom + x).toBeGreaterThanOrEqual(0);
      expect(position.y * zoom + y).toBeGreaterThanOrEqual(0);
      expect((position.x + position.width) * zoom + x).toBeLessThanOrEqual(1000);
      expect((position.y + position.height) * zoom + y).toBeLessThanOrEqual(600);
    }
  });

  it('fit is a no-op when there is nothing to fit', () => {
    store.resetViewport();
    store.fit(0, 0);

    expect(store.viewport()).toEqual({ x: 0, y: 0, zoom: 1 });
  });

  it('centres a node without changing the zoom', () => {
    store.zoomAt(0, 0, 2);
    store.centreOn('b', 800, 400);

    const { zoom, x, y } = store.viewport();
    expect(zoom).toBe(2);
    expect((300 + 100) * zoom + x).toBeCloseTo(400, 6);
    expect((200 + 32) * zoom + y).toBeCloseTo(200, 6);
  });

  it('pinning a node moves it immediately and records the override', () => {
    store.pin('a', 42, 84);

    expect(store.position('a')).toEqual({ x: 42, y: 84, width: 200, height: 64 });
    expect(store.pinned()['a']).toEqual({ x: 42, y: 84 });
  });

  it('emits an SVG transform string', () => {
    store.panBy(10, 20);
    store.zoomAt(0, 0, 2);

    expect(store.transform()).toBe('translate(20, 40) scale(2)');
  });
});

describe('node sizing', () => {
  const node = (displayName: string, type: GraphNode['type'] = 'SERVICE'): GraphNode => ({
    key: displayName,
    displayName,
    type,
    endpoints: [],
    warnings: [],
    metadata: {},
  });

  it('grows a box with its label, within bounds', () => {
    // Short names sit on the floor, long ones on the ceiling, and names in between scale.
    expect(nodeWidth(node('ab'))).toBe(168);
    expect(nodeWidth(node('a-service-with-a-very-long-name-indeed-really'))).toBe(280);
    expect(nodeWidth(node('log-notification-svc'))).toBeGreaterThan(168);
    expect(nodeWidth(node('log-notification-svc'))).toBeLessThan(280);
  });

  it('draws topics shorter than services', () => {
    expect(nodeHeight(node('order-events', 'TOPIC'))).toBeLessThan(nodeHeight(node('svc')));
  });
});

describe('contentBounds', () => {
  it('is null for an empty layout', () => {
    expect(contentBounds({ positions: {}, routes: {}, width: 0, height: 0 })).toBeNull();
  });

  it('spans every node', () => {
    const bounds = contentBounds({
      positions: {
        a: { x: -50, y: 10, width: 100, height: 50 },
        b: { x: 200, y: 300, width: 100, height: 50 },
      },
      routes: {},
      width: 0,
      height: 0,
    });

    expect(bounds).toEqual({ x: -50, y: 10, width: 350, height: 340 });
  });
});
