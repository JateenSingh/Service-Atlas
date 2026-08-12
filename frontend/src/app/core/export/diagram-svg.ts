import {
  Confidence,
  DependencyGraph,
  EdgeType,
  GraphNode,
  LayoutResult,
} from '../models/graph.models';

/**
 * Renders the current view as a standalone SVG document (FR-6.4).
 *
 * Built from the graph and layout rather than by serialising the live DOM: the on-screen canvas
 * gets its colours from CSS custom properties, which mean nothing once the file leaves the app.
 * Generating it here produces a file that opens correctly in any viewer, and gives the export the
 * same visual encoding as the canvas (FR-5.6) without depending on the browser's style resolution.
 */

export interface SvgPalette {
  background: string;
  surface: string;
  border: string;
  text: string;
  textMuted: string;
  external: string;
  topic: string;
  frameworks: Record<string, string>;
  edges: Record<EdgeType, string>;
}

export const DARK_PALETTE: SvgPalette = {
  background: '#0d1218',
  surface: '#121821',
  border: '#2a3644',
  text: '#e6edf3',
  textMuted: '#8496a6',
  external: '#64748b',
  topic: '#f472b6',
  frameworks: {
    'Play Framework': '#4ade80',
    'Akka HTTP': '#c084fc',
    'Pekko HTTP': '#a78bfa',
    http4s: '#38bdf8',
    gRPC: '#fbbf24',
    Unknown: '#94a3b8',
  },
  edges: {
    HTTP: '#60a5fa',
    ARTIFACT: '#a3a3a3',
    MESSAGING: '#f472b6',
    UNKNOWN: '#737373',
  },
};

export const LIGHT_PALETTE: SvgPalette = {
  background: '#ffffff',
  surface: '#ffffff',
  border: '#d3dae2',
  text: '#16202b',
  textMuted: '#6b7c8d',
  external: '#475569',
  topic: '#db2777',
  frameworks: {
    'Play Framework': '#16a34a',
    'Akka HTTP': '#7c3aed',
    'Pekko HTTP': '#6d28d9',
    http4s: '#0284c7',
    gRPC: '#b45309',
    Unknown: '#64748b',
  },
  edges: {
    HTTP: '#1d6fe0',
    ARTIFACT: '#64748b',
    MESSAGING: '#db2777',
    UNKNOWN: '#94a3b8',
  },
};

export interface SvgOptions {
  palette: SvgPalette;
  title?: string;
  /** Draws the legend explaining the encoding — on by default, since a shared diagram needs it. */
  legend?: boolean;
  padding?: number;
}

export function renderDiagramSvg(
  graph: DependencyGraph,
  layout: LayoutResult,
  options: SvgOptions,
): string {
  const palette = options.palette;
  const padding = options.padding ?? 48;
  const legendHeight = options.legend === false ? 0 : 96;

  const bounds = boundsOf(graph, layout);
  const width = Math.max(bounds.width + padding * 2, 480);
  const height = bounds.height + padding * 2 + legendHeight;
  const shift = (x: number, y: number) => ({
    x: x - bounds.x + padding,
    y: y - bounds.y + padding,
  });

  const parts: string[] = [];
  parts.push(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${round(width)}" height="${round(height)}" ` +
      `viewBox="0 0 ${round(width)} ${round(height)}" font-family="Inter, system-ui, sans-serif">`,
  );
  parts.push(defs(palette));
  parts.push(`<rect width="100%" height="100%" fill="${palette.background}"/>`);

  if (options.title) {
    parts.push(
      `<text x="${padding}" y="${padding - 18}" fill="${palette.textMuted}" font-size="13">` +
        `${escapeText(options.title)}</text>`,
    );
  }

  // Edges first so nodes sit on top of them, exactly as on the canvas.
  for (const edge of graph.edges) {
    const route = layout.routes[edge.id];
    const source = layout.positions[edge.sourceKey];
    const target = layout.positions[edge.targetKey];
    if (!source || !target) {
      continue;
    }
    const points = (
      route?.points ?? [
        { x: source.x + source.width, y: source.y + source.height / 2 },
        { x: target.x, y: target.y + target.height / 2 },
      ]
    ).map((point) => shift(point.x, point.y));

    const path = points
      .map((point, index) => `${index === 0 ? 'M' : 'L'} ${round(point.x)} ${round(point.y)}`)
      .join(' ');
    const dash = dashFor(edge.type);

    parts.push(
      `<path d="${path}" fill="none" stroke="${palette.edges[edge.type]}" stroke-width="1.6" ` +
        `opacity="${confidenceOpacity(edge.confidence)}"` +
        `${dash ? ` stroke-dasharray="${dash}"` : ''} ` +
        `marker-end="url(#arrow-${edge.type.toLowerCase()})"/>`,
    );

    if (edge.label) {
      const middle = points[Math.floor(points.length / 2)];
      parts.push(
        `<text x="${round(middle.x)}" y="${round(middle.y - 6)}" fill="${palette.textMuted}" ` +
          `font-size="10" font-family="ui-monospace, monospace" text-anchor="middle">` +
          `${escapeText(edge.label)}</text>`,
      );
    }
  }

  for (const node of graph.nodes) {
    const position = layout.positions[node.key];
    if (!position) {
      continue;
    }
    const { x, y } = shift(position.x, position.y);
    parts.push(nodeSvg(node, x, y, position.width, position.height, palette));
  }

  if (options.legend !== false) {
    parts.push(legendSvg(padding, height - legendHeight + 16, palette));
  }

  parts.push('</svg>');
  return parts.join('\n');
}

function defs(palette: SvgPalette): string {
  const markers = (Object.keys(palette.edges) as EdgeType[])
    .map(
      (type) =>
        `<marker id="arrow-${type.toLowerCase()}" viewBox="0 0 10 10" refX="9" refY="5" ` +
        `markerWidth="7" markerHeight="7" orient="auto-start-reverse">` +
        `<path d="M 0 0 L 10 5 L 0 10 z" fill="${palette.edges[type]}"/></marker>`,
    )
    .join('');
  return `<defs>${markers}</defs>`;
}

function nodeSvg(
  node: GraphNode,
  x: number,
  y: number,
  width: number,
  height: number,
  palette: SvgPalette,
): string {
  const colour = nodeColour(node, palette);
  const parts: string[] = [`<g transform="translate(${round(x)},${round(y)})">`];

  if (node.type === 'TOPIC') {
    parts.push(
      `<rect width="${round(width)}" height="${round(height)}" rx="${round(height / 2)}" ` +
        `fill="${palette.surface}" stroke="${palette.topic}" stroke-width="1.2"/>`,
    );
    parts.push(
      `<text x="16" y="${round(height / 2 + 4)}" fill="${palette.text}" font-size="12" ` +
        `font-family="ui-monospace, monospace">${escapeText(node.displayName)}</text>`,
    );
  } else {
    const dashed = node.type === 'EXTERNAL' ? ' stroke-dasharray="5 4"' : '';
    parts.push(
      `<rect width="${round(width)}" height="${round(height)}" rx="10" ` +
        `fill="${node.type === 'EXTERNAL' ? 'none' : palette.surface}" ` +
        `stroke="${node.type === 'EXTERNAL' ? palette.external : palette.border}" ` +
        `stroke-width="1"${dashed}/>`,
    );
    parts.push(`<rect x="0" y="8" width="4" height="${round(height - 16)}" fill="${colour}"/>`);
    parts.push(
      `<text x="16" y="27" fill="${palette.text}" font-size="13.5" font-weight="600">` +
        `${escapeText(node.displayName)}</text>`,
    );
    parts.push(
      `<text x="16" y="45" fill="${colour}" font-size="10.5" font-weight="500">` +
        `${escapeText(subtitle(node))}</text>`,
    );
  }

  parts.push('</g>');
  return parts.join('');
}

function legendSvg(x: number, y: number, palette: SvgPalette): string {
  const entries: Array<[string, string, string | null]> = [
    ['HTTP call', palette.edges.HTTP, null],
    ['Messaging', palette.edges.MESSAGING, '8 5'],
    ['Build artifact', palette.edges.ARTIFACT, '2 4'],
  ];

  const parts: string[] = [`<g transform="translate(${round(x)},${round(y)})">`];
  parts.push(
    `<text x="0" y="0" fill="${palette.textMuted}" font-size="11" font-weight="600" ` +
      `letter-spacing="0.08em">LEGEND</text>`,
  );

  entries.forEach(([label, colour, dash], index) => {
    const offsetY = 22 + index * 18;
    parts.push(
      `<line x1="0" y1="${offsetY}" x2="28" y2="${offsetY}" stroke="${colour}" stroke-width="1.8"` +
        `${dash ? ` stroke-dasharray="${dash}"` : ''}/>`,
    );
    parts.push(
      `<text x="38" y="${offsetY + 4}" fill="${palette.textMuted}" font-size="11">` +
        `${escapeText(label)}</text>`,
    );
  });

  parts.push(
    `<text x="180" y="22" fill="${palette.textMuted}" font-size="11">` +
      `Line opacity shows confidence · dashed outline means the service was not found locally</text>`,
  );
  parts.push('</g>');
  return parts.join('');
}

export function nodeColour(node: GraphNode, palette: SvgPalette): string {
  if (node.type === 'TOPIC') {
    return palette.topic;
  }
  if (node.type === 'EXTERNAL') {
    return palette.external;
  }
  return palette.frameworks[node.framework ?? 'Unknown'] ?? palette.frameworks['Unknown'];
}

export function dashFor(type: EdgeType): string | null {
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

export function confidenceOpacity(confidence: Confidence): number {
  switch (confidence) {
    case 'HIGH':
      return 0.95;
    case 'MEDIUM':
      return 0.66;
    default:
      return 0.4;
  }
}

function subtitle(node: GraphNode): string {
  switch (node.type) {
    case 'EXTERNAL':
      return 'external';
    case 'SUB_MODULE':
      return 'module';
    default:
      return node.framework && node.framework !== 'Unknown' ? node.framework : 'service';
  }
}

export function boundsOf(
  graph: DependencyGraph,
  layout: LayoutResult,
): { x: number; y: number; width: number; height: number } {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;

  for (const node of graph.nodes) {
    const position = layout.positions[node.key];
    if (!position) {
      continue;
    }
    minX = Math.min(minX, position.x);
    minY = Math.min(minY, position.y);
    maxX = Math.max(maxX, position.x + position.width);
    maxY = Math.max(maxY, position.y + position.height);
  }
  for (const route of Object.values(layout.routes)) {
    for (const point of route.points) {
      minX = Math.min(minX, point.x);
      minY = Math.min(minY, point.y);
      maxX = Math.max(maxX, point.x);
      maxY = Math.max(maxY, point.y);
    }
  }

  if (!Number.isFinite(minX)) {
    return { x: 0, y: 0, width: 400, height: 200 };
  }
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}

function escapeText(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
