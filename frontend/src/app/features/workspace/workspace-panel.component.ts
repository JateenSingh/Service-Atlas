import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { GraphStore } from '../../core/state/graph.store';
import { ServiceAtlasApi, problemMessage } from '../../core/api/service-atlas-api.service';
import { firstValueFrom } from 'rxjs';
import { RootPathPreview, Workspace } from '../../core/models/graph.models';
import { ScanProgressComponent } from './scan-progress.component';

/**
 * Workspace creation and selection (FR-1, UX-2).
 *
 * The path field is plain text on purpose: the backend reads the filesystem directly and the
 * browser's directory picker returns a sandboxed handle, not a path the server could use. A live
 * preview (FR-1.2) makes the manual path safe — you see the repository count before you commit.
 */
@Component({
  selector: 'sa-workspace-panel',
  standalone: true,
  imports: [FormsModule, ScanProgressComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './workspace-panel.component.html',
  styleUrl: './workspace-panel.component.css',
})
export class WorkspacePanelComponent {
  readonly store = inject(WorkspaceStore);
  private readonly graphStore = inject(GraphStore);
  private readonly api = inject(ServiceAtlasApi);

  readonly name = signal('');
  readonly rootPath = signal('');
  readonly maxDepth = signal(3);
  readonly ignored = signal('');

  readonly preview = signal<RootPathPreview | null>(null);
  readonly previewing = signal(false);
  readonly formError = signal<string | null>(null);
  readonly creating = signal(false);
  readonly showAdvanced = signal(false);

  readonly canCreate = computed(
    () => this.name().trim().length > 0 && this.rootPath().trim().length > 0 && !this.creating(),
  );

  readonly showForm = computed(() => !this.store.hasWorkspaces() || this.formOpen());
  private readonly formOpen = signal(false);

  openForm(): void {
    this.formOpen.set(true);
  }

  closeForm(): void {
    this.formOpen.set(false);
    this.formError.set(null);
    this.preview.set(null);
  }

  /** FR-1.2 — check the folder before creating anything. */
  async checkPath(): Promise<void> {
    const path = this.rootPath().trim();
    if (!path) {
      return;
    }
    this.previewing.set(true);
    this.formError.set(null);
    try {
      const preview = await this.store.preview(path, this.settings());
      this.preview.set(preview);
      if (!this.name().trim()) {
        this.name.set(defaultNameFor(preview.rootPath));
      }
    } catch (problem) {
      this.preview.set(null);
      this.formError.set(problemMessage(problem));
    } finally {
      this.previewing.set(false);
    }
  }

  async create(): Promise<void> {
    if (!this.canCreate()) {
      return;
    }
    this.creating.set(true);
    this.formError.set(null);
    try {
      const workspace = await this.store.create(
        this.name().trim(),
        this.rootPath().trim(),
        this.settings(),
      );
      this.reset();
      this.formOpen.set(false);
      await this.store.startScan(workspace.id);
    } catch (problem) {
      this.formError.set(problemMessage(problem));
    } finally {
      this.creating.set(false);
    }
  }

  async select(workspace: Workspace): Promise<void> {
    this.store.select(workspace.id);
    this.graphStore.reset();
    await this.graphStore.load(workspace.id);
  }

  async scan(workspace: Workspace): Promise<void> {
    await this.store.startScan(workspace.id);
  }

  async remove(workspace: Workspace, event: Event): Promise<void> {
    event.stopPropagation();
    if (!confirm(`Delete workspace "${workspace.name}"? Scans and manual edits go with it.`)) {
      return;
    }
    await this.store.remove(workspace.id);
    this.graphStore.reset();
  }

  toggleAdvanced(): void {
    this.showAdvanced.update((shown) => !shown);
  }

  // ------------------------------------------------------------------ bundles (FR-1.4)

  readonly importing = signal(false);

  /** Downloads the workspace as a portable .atlas file — graph and edits, never source code. */
  exportBundle(workspace: Workspace, event: Event): void {
    event.stopPropagation();
    const anchor = document.createElement('a');
    anchor.href = this.api.bundleUrl(workspace.id);
    anchor.download = '';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  }

  async importBundle(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.importing.set(true);
    this.formError.set(null);
    try {
      const workspace = await firstValueFrom(this.api.importBundle(file));
      await this.store.load();
      this.store.select(workspace.id);
      await this.graphStore.load(workspace.id);
    } catch (problem) {
      this.formError.set(problemMessage(problem));
    } finally {
      this.importing.set(false);
      input.value = ''; // so re-picking the same file fires change again
    }
  }

  private settings() {
    const ignoredDirectories = this.ignored()
      .split(',')
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0);
    return { maxDepth: this.maxDepth(), ignoredDirectories };
  }

  private reset(): void {
    this.name.set('');
    this.rootPath.set('');
    this.ignored.set('');
    this.maxDepth.set(3);
    this.preview.set(null);
  }
}

function defaultNameFor(path: string): string {
  const segments = path.split(/[\\/]/).filter(Boolean);
  return segments[segments.length - 1] ?? 'Workspace';
}
