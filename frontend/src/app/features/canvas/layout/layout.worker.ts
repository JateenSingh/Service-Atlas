/// <reference lib="webworker" />

/**
 * Runs ELK's layered algorithm off the UI thread (ADR-001, FR-5.2).
 *
 * This file is the <em>only</em> place in the app that imports `elkjs`. Everything else talks to
 * the {@link LayoutEngine} interface, so the engine can be replaced without touching the canvas.
 */

import { ElkNode, ElkExtendedEdge } from 'elkjs/lib/elk.bundled.js';
type ElkInstance = { layout(graph: ElkNode): Promise<ElkNode> };
type ElkConstructor = new (options?: unknown) => ElkInstance;

/**
 * `elk.bundled.js` is a UMD build, and which shape survives bundling depends on how CommonJS
 * interop is applied in this context (module worker vs main thread). Rather than guessing, try
 * every shape it can present and fail with a message that says what actually went wrong.
 */
let elkInstance: Promise<ElkInstance> | null = null;

function elk(): Promise<ElkInstance> {
  elkInstance ??= import('elkjs/lib/elk.bundled.js').then((module: unknown) => {
    const candidates = [
      (module as { default?: unknown })?.default,
      module,
      (globalThis as { ELK?: unknown }).ELK,
    ];
    const Constructor = candidates.find(
      (candidate): candidate is ElkConstructor => typeof candidate === 'function',
    );
    if (!Constructor) {
      throw new Error('elkjs did not expose a constructor in this environment');
    }
    return new Constructor();
  });
  return elkInstance;
}

import { LayoutRequest } from './layout-engine';
import { EdgeRoute, LayoutResult, NodePosition } from '../../../core/models/graph.models';


/**
 * ELK options, defined once and commented, because they are stringly typed and version-sensitive.
 */
function layoutOptions(direction: 'RIGHT' | 'DOWN'): Record<string, string> {
  return {
    'elk.algorithm': 'layered',
    'elk.direction': direction,
    // Orthogonal routing with merged edge segments: the readable default for architecture diagrams.
    'elk.edgeRouting': 'ORTHOGONAL',
    'elk.layered.mergeEdges': 'true',
    // Crossing minimisation dominates readability at this graph size; the extra passes are cheap.
    'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
    'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
    'elk.layered.cycleBreaking.strategy': 'GREEDY',
    // Generous spacing: a dependency diagram is read, not admired for its density.
    'elk.spacing.nodeNode': '56',
    'elk.layered.spacing.nodeNodeBetweenLayers': '110',
    'elk.spacing.edgeNode': '28',
    'elk.spacing.edgeEdge': '18',
    'elk.padding': '[top=48,left=48,bottom=48,right=48]',
    'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
  };
}

addEventListener('message', ({ data }: MessageEvent<{ id: number; request: LayoutRequest }>) => {
  const { id, request } = data;
  run(request)
    .then((result) => postMessage({ id, result }))
    .catch((error: unknown) =>
      postMessage({ id, error: error instanceof Error ? error.message : String(error) }),
    );
});

async function run(request: LayoutRequest): Promise<LayoutResult> {
  const children: ElkNode[] = request.nodes.map((node) => ({
    id: node.key,
    width: node.width,
    height: node.height,
  }));

  const edges: ElkExtendedEdge[] = request.edges.map((edge) => ({
    id: edge.id,
    sources: [edge.sourceKey],
    targets: [edge.targetKey],
  }));

  const graph: ElkNode = {
    id: 'root',
    layoutOptions: layoutOptions(request.direction),
    children,
    edges,
  };

  const laid = await (await elk()).layout(graph);
  return toResult(laid, request);
}

function toResult(laid: ElkNode, request: LayoutRequest): LayoutResult {
  const positions: Record<string, NodePosition> = {};
  const requested = new Map(request.nodes.map((node) => [node.key, node]));

  for (const child of laid.children ?? []) {
    const source = requested.get(child.id);
    positions[child.id] = {
      x: finite(child.x),
      y: finite(child.y),
      width: child.width ?? source?.width ?? 180,
      height: child.height ?? source?.height ?? 64,
    };
  }

  // A node ELK dropped (or one added between request and response) still needs a position, or the
  // canvas would render it at the origin on top of everything else.
  let fallbackY = 0;
  for (const node of request.nodes) {
    if (!positions[node.key]) {
      positions[node.key] = { x: -260, y: fallbackY, width: node.width, height: node.height };
      fallbackY += node.height + 24;
    }
  }

  const routes: Record<string, EdgeRoute> = {};
  for (const edge of laid.edges ?? []) {
    const points: Array<{ x: number; y: number }> = [];
    for (const section of edge.sections ?? []) {
      points.push({ x: finite(section.startPoint.x), y: finite(section.startPoint.y) });
      for (const bend of section.bendPoints ?? []) {
        points.push({ x: finite(bend.x), y: finite(bend.y) });
      }
      points.push({ x: finite(section.endPoint.x), y: finite(section.endPoint.y) });
    }
    if (points.length >= 2) {
      routes[edge.id] = { id: edge.id, points };
    }
  }

  return {
    positions,
    routes,
    width: finite(laid.width, 1200),
    height: finite(laid.height, 800),
  };
}

function finite(value: number | undefined, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}
