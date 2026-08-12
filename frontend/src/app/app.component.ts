import { ChangeDetectionStrategy, Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { GraphStore } from './core/state/graph.store';
import { LayoutStore } from './core/state/layout.store';
import { ThemeStore } from './core/state/theme.store';
import { WorkspaceStore } from './core/state/workspace.store';
import { CanvasPageComponent } from './features/canvas/canvas-page.component';
import { WorkspacePanelComponent } from './features/workspace/workspace-panel.component';
import { ExportDialogComponent } from './features/export/export-dialog.component';

type Screen = 'workspaces' | 'canvas';

/**
 * Application shell.
 *
 * Two screens rather than a router: the app is a single tool with one modal step (choose a
 * workspace) and one working surface (the canvas). A router would add URL state that means nothing
 * outside this machine — the workspace list is not shareable, and deep links to a local scan are
 * not a thing anyone wants.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CanvasPageComponent, WorkspacePanelComponent, ExportDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly theme = inject(ThemeStore);
  readonly workspaceStore = inject(WorkspaceStore);
  readonly graphStore = inject(GraphStore);
  readonly layoutStore = inject(LayoutStore);

  private readonly screenSignal = signal<Screen>('workspaces');
  readonly exportOpen = signal(false);

  readonly screen = this.screenSignal.asReadonly();
  readonly currentTheme = this.theme.theme;

  readonly canOpenCanvas = computed(
    () => this.workspaceStore.selected() !== null && !this.graphStore.isEmpty(),
  );

  constructor() {
    void this.workspaceStore.load();

    // When a scan finishes, pull the new graph and move the user to it — the point of scanning.
    effect(() => {
      const scan = this.workspaceStore.activeScan();
      const workspace = this.workspaceStore.selected();
      if (!scan || !workspace || this.workspaceStore.scanning()) {
        return;
      }
      if (scan.status === 'COMPLETED') {
        void this.graphStore.load(workspace.id).then(() => {
          if (!this.graphStore.isEmpty()) {
            this.screenSignal.set('canvas');
          }
        });
      }
    });

    // Load the graph when the selected workspace changes without a scan.
    effect(() => {
      const workspace = this.workspaceStore.selected();
      if (workspace && !this.workspaceStore.scanning()) {
        void this.graphStore.load(workspace.id).then(() => this.openCanvasOnFirstLoad());
      }
    });
  }

  /**
   * On startup, land on the diagram when there already is one. Reloading the page while looking at
   * a graph should not dump you back on the workspace picker.
   *
   * <p>Only ever fires once: after that the screen is the user's choice, and pulling them back to
   * the canvas when they deliberately opened the workspace screen would be worse than the problem.
   */
  private hasAutoOpened = false;

  private openCanvasOnFirstLoad(): void {
    if (this.hasAutoOpened || this.graphStore.isEmpty()) {
      return;
    }
    this.hasAutoOpened = true;
    this.screenSignal.set('canvas');
  }

  showCanvas(): void {
    this.screenSignal.set('canvas');
  }

  showWorkspaces(): void {
    this.hasAutoOpened = true;
    this.screenSignal.set('workspaces');
  }

  toggleTheme(): void {
    this.theme.toggle();
  }

  openExport(): void {
    this.exportOpen.set(true);
  }

  closeExport(): void {
    this.exportOpen.set(false);
  }

  /** UX-6: F fits to screen, / focuses search, Esc clears the selection. */
  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    const target = event.target as HTMLElement | null;
    const typing =
      target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA' || target?.isContentEditable;

    if (event.key === 'Escape') {
      if (this.exportOpen()) {
        this.closeExport();
      } else if (typing) {
        (target as HTMLInputElement).blur();
      } else {
        this.graphStore.clearSelection();
      }
      return;
    }

    if (typing || event.metaKey || event.ctrlKey || event.altKey) {
      return;
    }

    if (event.key === '/') {
      event.preventDefault();
      document.querySelector<HTMLInputElement>('.search input')?.focus();
      return;
    }

    if (event.key === 'f' || event.key === 'F') {
      event.preventDefault();
      document.querySelector<HTMLButtonElement>('[title^="Fit to screen"]')?.click();
    }
  }
}
