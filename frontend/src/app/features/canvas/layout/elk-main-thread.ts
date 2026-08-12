/**
 * Main-thread fallback for {@link ElkLayoutEngine}, used only where `Worker` is unavailable.
 *
 * Lazily imported, so the ELK bundle is still kept out of the initial chunk in the normal case.
 */

import { ElkNode } from 'elkjs/lib/elk.bundled.js';
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

import { EdgeRoute, LayoutResult, NodePosition } from '../../../core/models/graph.models';
import { LayoutRequest } from './layout-engine';


export async function layoutWithElk(request: LayoutRequest): Promise<LayoutResult> {
  const graph: ElkNode = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': request.direction,
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.spacing.nodeNode': '56',
      'elk.layered.spacing.nodeNodeBetweenLayers': '110',
      'elk.padding': '[top=48,left=48,bottom=48,right=48]',
    },
    children: request.nodes.map((node) => ({
      id: node.key,
      width: node.width,
      height: node.height,
    })),
    edges: request.edges.map((edge) => ({
      id: edge.id,
      sources: [edge.sourceKey],
      targets: [edge.targetKey],
    })),
  };

  const laid = await (await elk()).layout(graph);

  const positions: Record<string, NodePosition> = {};
  for (const child of laid.children ?? []) {
    positions[child.id] = {
      x: child.x ?? 0,
      y: child.y ?? 0,
      width: child.width ?? 180,
      height: child.height ?? 64,
    };
  }

  const routes: Record<string, EdgeRoute> = {};
  for (const edge of laid.edges ?? []) {
    const points: Array<{ x: number; y: number }> = [];
    for (const section of edge.sections ?? []) {
      points.push({ x: section.startPoint.x, y: section.startPoint.y });
      for (const bend of section.bendPoints ?? []) {
        points.push({ x: bend.x, y: bend.y });
      }
      points.push({ x: section.endPoint.x, y: section.endPoint.y });
    }
    if (points.length >= 2) {
      routes[edge.id] = { id: edge.id, points };
    }
  }

  return { positions, routes, width: laid.width ?? 0, height: laid.height ?? 0 };
}
