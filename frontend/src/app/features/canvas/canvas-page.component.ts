import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GraphStore } from '../../core/state/graph.store';
import { LayoutStore } from '../../core/state/layout.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { Confidence, EDGE_TYPES, EdgeType, GraphNode } from '../../core/models/graph.models';
import { GraphCanvasComponent } from './graph-canvas.component';
import { InspectorPanelComponent } from '../inspector/inspector-panel.component';
import { LayoutDirection } from './layout/layout-engine';

/**
 * The canvas screen (UX-4): service list on the left, diagram in the middle, inspector on the
 * right, and a toolbar carrying layout direction, filters and export.
 */
@Component({
  selector: 'sa-canvas-page',
  standalone: true,
  imports: [FormsModule, GraphCanvasComponent, InspectorPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './canvas-page.component.html',
  styleUrl: './canvas-page.component.css',
})
export class CanvasPageComponent {
  readonly graphStore = inject(GraphStore);
  readonly layoutStore = inject(LayoutStore);
  readonly workspaceStore = inject(WorkspaceStore);

  private readonly canvas = viewChild(GraphCanvasComponent);
  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  readonly edgeTypes = EDGE_TYPES;
  readonly confidences: Confidence[] = ['LOW', 'MEDIUM', 'HIGH'];

  readonly sidebarCollapsed = signal(false);
  readonly filtersOpen = signal(false);
  readonly conflictsVisible = signal(true);

  /** The service list mirrors the filtered view, so what is listed is what is drawn. */
  readonly listedNodes = computed(() =>
    [...this.graphStore.visibleGraph().nodes].sort((a, b) => {
      const byType = typeRank(a) - typeRank(b);
      return byType !== 0 ? byType : a.displayName.localeCompare(b.displayName);
    }),
  );

  readonly selectedKey = computed(() => {
    const selection = this.graphStore.selection();
    return selection.kind === 'node' ? selection.key : null;
  });

  readonly searchValue = computed(() => this.graphStore.filters().search);

  isEdgeTypeOn(type: EdgeType): boolean {
    return this.graphStore.filters().edgeTypes.has(type);
  }

  toggleEdgeType(type: EdgeType): void {
    this.graphStore.toggleEdgeType(type);
  }

  setConfidence(confidence: Confidence): void {
    this.graphStore.setMinConfidence(confidence);
  }

  toggleExternal(): void {
    this.graphStore.setShowExternal(!this.graphStore.filters().showExternal);
  }

  toggleDatastores(): void {
    this.graphStore.setShowDatastores(!this.graphStore.filters().showDatastores);
  }

  setDirection(direction: LayoutDirection): void {
    this.layoutStore.setDirection(direction);
  }

  onSearch(value: string): void {
    this.graphStore.setSearch(value);
  }

  selectFromList(node: GraphNode): void {
    this.graphStore.selectNode(node.key);
    this.canvas()?.centreOnNode(node.key);
  }

  fitToScreen(): void {
    this.canvas()?.fitToScreen();
  }

  focusSearch(): void {
    this.searchInput()?.nativeElement.focus();
    this.searchInput()?.nativeElement.select();
  }

  clearSelection(): void {
    this.graphStore.clearSelection();
  }

  resetFilters(): void {
    this.graphStore.resetFilters();
  }

  toggleSidebar(): void {
    this.sidebarCollapsed.update((collapsed) => !collapsed);
  }

  toggleFilters(): void {
    this.filtersOpen.update((open) => !open);
  }

  async showAll(): Promise<void> {
    const hiddenNodes = this.graphStore.hiddenNodes();
    const hiddenEdges = this.graphStore.hiddenEdges();
    for (const node of hiddenNodes) {
      await this.graphStore.unhideNode(node.key);
    }
    for (const edge of hiddenEdges) {
      await this.graphStore.unhideEdge(edge.id);
    }
  }

  dismissConflicts(): void {
    this.conflictsVisible.set(false);
  }

  async rescan(): Promise<void> {
    const workspace = this.workspaceStore.selected();
    if (workspace) {
      this.conflictsVisible.set(true);
      await this.workspaceStore.startScan(workspace.id);
    }
  }

  nodeKindLabel(node: GraphNode): string {
    switch (node.type) {
      case 'TOPIC':
        return String(node.metadata?.['broker'] ?? 'topic').toLowerCase();
      case 'DATASTORE':
        return String(node.metadata?.['engine'] ?? 'datastore').toLowerCase();
      case 'EXTERNAL':
        return 'external';
      case 'SUB_MODULE':
        return 'module';
      default:
        return node.framework && node.framework !== 'Unknown' ? node.framework : 'service';
    }
  }
}

function typeRank(node: GraphNode): number {
  switch (node.type) {
    case 'SERVICE':
      return 0;
    case 'SUB_MODULE':
      return 1;
    case 'DATASTORE':
      return 2;
    case 'TOPIC':
      return 3;
    default:
      return 4;
  }
}
