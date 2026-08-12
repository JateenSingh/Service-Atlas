import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { GraphStore } from '../../core/state/graph.store';
import { GraphEdge, GraphNode, SIGNAL_SOURCE_LABELS } from '../../core/models/graph.models';
import { nodeTypeLabel } from '../canvas/graph-canvas.component';

/**
 * The detail panel (UX-4, FR-5.3, FR-5.4).
 *
 * Its job is to answer "why is this here?". Every edge shows the file and line that produced it,
 * because a MEDIUM-confidence guess the user cannot check is worse than no guess at all.
 */
@Component({
  selector: 'sa-inspector-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './inspector-panel.component.html',
  styleUrl: './inspector-panel.component.css',
})
export class InspectorPanelComponent {
  private readonly graphStore = inject(GraphStore);

  readonly node = this.graphStore.selectedNode;
  readonly edge = this.graphStore.selectedEdge;
  readonly relations = this.graphStore.selectedNodeEdges;
  readonly nodesByKey = this.graphStore.nodesByKey;

  readonly hasSelection = computed(() => this.node() !== null || this.edge() !== null);

  readonly metadataEntries = computed(() => {
    const node = this.node();
    if (!node) {
      return [];
    }
    return Object.entries(node.metadata)
      .filter(([, value]) => value !== null && value !== undefined && value !== '')
      .map(([key, value]) => ({ key: humanise(key), value: formatValue(value) }));
  });

  displayName(key: string): string {
    return this.nodesByKey().get(key)?.displayName ?? key;
  }

  typeLabel(node: GraphNode): string {
    return nodeTypeLabel(node.type);
  }

  sourceLabel(source: keyof typeof SIGNAL_SOURCE_LABELS): string {
    return SIGNAL_SOURCE_LABELS[source] ?? source;
  }

  select(key: string): void {
    this.graphStore.selectNode(key);
  }

  selectEdge(edge: GraphEdge): void {
    this.graphStore.selectEdge(edge.id);
  }

  focus(key: string): void {
    this.graphStore.toggleFocus(key);
  }

  close(): void {
    this.graphStore.clearSelection();
  }
}

function humanise(key: string): string {
  return key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/^./, (character) => character.toUpperCase());
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) {
    return value.join(', ');
  }
  return String(value);
}
