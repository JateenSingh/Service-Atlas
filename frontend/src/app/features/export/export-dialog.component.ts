import { UpperCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { GraphStore } from '../../core/state/graph.store';
import { LayoutStore } from '../../core/state/layout.store';
import { ThemeStore } from '../../core/state/theme.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { DARK_PALETTE, LIGHT_PALETTE, renderDiagramSvg } from '../../core/export/diagram-svg';

export type ExportFormat = 'svg' | 'png' | 'json';

/**
 * Export dialog (UX-5, FR-6.3, FR-6.4).
 *
 * Everything it writes comes from `visibleGraph`, so "export what I see" is structural rather than
 * a rule someone has to remember.
 */
@Component({
  selector: 'sa-export-dialog',
  standalone: true,
  imports: [UpperCasePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './export-dialog.component.html',
  styleUrl: './export-dialog.component.css',
})
export class ExportDialogComponent {
  private readonly graphStore = inject(GraphStore);
  private readonly layoutStore = inject(LayoutStore);
  private readonly workspaceStore = inject(WorkspaceStore);
  private readonly theme = inject(ThemeStore);

  readonly closed = output<void>();

  readonly format = signal<ExportFormat>('svg');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly done = signal<string | null>(null);

  readonly counts = computed(() => ({
    nodes: this.graphStore.visibleNodeCount(),
    edges: this.graphStore.visibleEdgeCount(),
    hiddenNodes: this.graphStore.hiddenNodeCount(),
    hiddenEdges: this.graphStore.hiddenEdgeCount(),
  }));

  select(format: ExportFormat): void {
    this.format.set(format);
    this.done.set(null);
    this.error.set(null);
  }

  close(): void {
    this.closed.emit();
  }

  async run(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.done.set(null);
    try {
      switch (this.format()) {
        case 'svg':
          this.downloadSvg();
          break;
        case 'png':
          await this.downloadPng();
          break;
        case 'json':
          this.downloadJson();
          break;
      }
      this.done.set(this.format());
    } catch (failure) {
      this.error.set(failure instanceof Error ? failure.message : 'Export failed.');
    } finally {
      this.busy.set(false);
    }
  }

  private svgSource(): string {
    return renderDiagramSvg(this.graphStore.visibleGraph(), this.layoutStore.layout(), {
      palette: this.theme.theme() === 'dark' ? DARK_PALETTE : LIGHT_PALETTE,
      title: this.workspaceStore.selected()?.name,
      legend: true,
    });
  }

  private downloadSvg(): void {
    download(this.baseName() + '.svg', new Blob([this.svgSource()], { type: 'image/svg+xml' }));
  }

  /** FR-6.4 — PNG by canvas serialisation, at 2× for a usable resolution in slides and docs. */
  private async downloadPng(): Promise<void> {
    const source = this.svgSource();
    const image = new Image();
    const url = URL.createObjectURL(new Blob([source], { type: 'image/svg+xml' }));

    try {
      await new Promise<void>((resolve, reject) => {
        image.onload = () => resolve();
        image.onerror = () => reject(new Error('The diagram could not be rasterised.'));
        image.src = url;
      });

      const scale = 2;
      const canvas = document.createElement('canvas');
      canvas.width = (image.naturalWidth || 1200) * scale;
      canvas.height = (image.naturalHeight || 800) * scale;
      const context = canvas.getContext('2d');
      if (!context) {
        throw new Error('This browser cannot render to a canvas.');
      }
      context.scale(scale, scale);
      context.drawImage(image, 0, 0);

      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
      if (!blob) {
        throw new Error('The PNG could not be encoded.');
      }
      download(this.baseName() + '.png', blob);
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  private downloadJson(): void {
    const payload = {
      workspace: this.workspaceStore.selected()?.name ?? null,
      scanId: this.graphStore.scanId(),
      exportedAt: new Date().toISOString(),
      filters: {
        edgeTypes: [...this.graphStore.filters().edgeTypes],
        minConfidence: this.graphStore.filters().minConfidence,
        showExternal: this.graphStore.filters().showExternal,
        search: this.graphStore.filters().search,
      },
      graph: this.graphStore.visibleGraph(),
      layout: this.layoutStore.layout(),
    };
    download(
      this.baseName() + '.json',
      new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }),
    );
  }

  private baseName(): string {
    const name = this.workspaceStore.selected()?.name ?? 'service-atlas';
    return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '') || 'service-atlas';
  }
}

export function download(fileName: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // Revoked on the next tick: revoking synchronously can cancel the download in some browsers.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
