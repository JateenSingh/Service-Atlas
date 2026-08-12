import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { API_BASE, problemMessage } from '../../core/api/service-atlas-api.service';
import { GraphStore } from '../../core/state/graph.store';
import { LayoutStore } from '../../core/state/layout.store';
import { ThemeStore } from '../../core/state/theme.store';
import { WorkspaceStore } from '../../core/state/workspace.store';

export type ExportFormat = 'lucid' | 'svg' | 'png' | 'json';

interface LucidStatus {
  configured: boolean;
  connected: boolean;
}

/**
 * Export dialog (UX-5, FR-6).
 *
 * <p>Every format is rendered by the backend from the view posted here, so the four outputs cannot
 * drift apart and the Lucid schema stays in one place (ADR-003). What gets posted is
 * `visibleGraph` plus the current layout — which is what makes "export what I see" (FR-6.3)
 * structural rather than a rule someone has to remember.
 */
@Component({
  selector: 'sa-export-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './export-dialog.component.html',
  styleUrl: './export-dialog.component.css',
})
export class ExportDialogComponent {
  private readonly http = inject(HttpClient);
  private readonly graphStore = inject(GraphStore);
  private readonly layoutStore = inject(LayoutStore);
  private readonly workspaceStore = inject(WorkspaceStore);
  private readonly theme = inject(ThemeStore);

  readonly closed = output<void>();

  readonly format = signal<ExportFormat>('lucid');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly done = signal<ExportFormat | null>(null);
  readonly lucidStatus = signal<LucidStatus>({ configured: false, connected: false });
  readonly pushedUrl = signal<string | null>(null);

  readonly counts = computed(() => ({
    nodes: this.graphStore.visibleNodeCount(),
    edges: this.graphStore.visibleEdgeCount(),
    hiddenNodes: this.graphStore.hiddenNodeCount(),
    hiddenEdges: this.graphStore.hiddenEdgeCount(),
  }));

  constructor() {
    // FR-6.2: the API option is hidden entirely unless the server has credentials.
    const workspace = this.workspaceStore.selected();
    if (workspace) {
      void firstValueFrom(
        this.http.get<LucidStatus>(`${API_BASE}/workspaces/${workspace.id}/export/lucid-api/status`),
      )
        .then((status) => this.lucidStatus.set(status))
        .catch(() => this.lucidStatus.set({ configured: false, connected: false }));
    }
  }

  select(format: ExportFormat): void {
    this.format.set(format);
    this.done.set(null);
    this.error.set(null);
    this.pushedUrl.set(null);
  }

  close(): void {
    this.closed.emit();
  }

  async run(): Promise<void> {
    const workspace = this.workspaceStore.selected();
    if (!workspace) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.done.set(null);

    try {
      const format = this.format();
      const url =
        format === 'lucid'
          ? `${API_BASE}/workspaces/${workspace.id}/export/lucid`
          : `${API_BASE}/workspaces/${workspace.id}/export/${format}`;

      const blob = await firstValueFrom(
        this.http.post(url, this.diagramView(), { responseType: 'blob' }),
      );
      download(`${slug(workspace.name)}.${format}`, blob);
      this.done.set(format);
    } catch (problem) {
      this.error.set(await readProblem(problem));
    } finally {
      this.busy.set(false);
    }
  }

  /** FR-6.2 — push straight into the user's Lucid account, when it is configured. */
  async pushToLucid(): Promise<void> {
    const workspace = this.workspaceStore.selected();
    if (!workspace) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const created = await firstValueFrom(
        this.http.post<{ editUrl: string }>(
          `${API_BASE}/workspaces/${workspace.id}/export/lucid-api`,
          this.diagramView(),
        ),
      );
      this.pushedUrl.set(created.editUrl);
    } catch (problem) {
      this.error.set(await readProblem(problem));
    } finally {
      this.busy.set(false);
    }
  }

  /** The exact view on screen: filtered graph, current layout, current theme. */
  private diagramView() {
    const layout = this.layoutStore.layout();
    return {
      title: this.workspaceStore.selected()?.name ?? 'Service Atlas',
      graph: this.graphStore.visibleGraph(),
      positions: layout.positions,
      routes: layout.routes,
      theme: this.theme.theme(),
    };
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

function slug(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '') || 'service-atlas';
}

/**
 * Problem details arrive as a Blob when the request asked for one, so the server's explanation has
 * to be read back out rather than shown as "[object Blob]".
 */
async function readProblem(problem: unknown): Promise<string> {
  const body = (problem as { error?: unknown })?.error;
  if (body instanceof Blob) {
    try {
      return problemMessage(JSON.parse(await body.text()));
    } catch {
      return 'Export failed.';
    }
  }
  return problemMessage(problem);
}
