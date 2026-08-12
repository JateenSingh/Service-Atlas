import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ServiceAtlasApi, problemMessage } from '../api/service-atlas-api.service';
import {
  RepoProgress,
  RootPathPreview,
  ScanStatusResponse,
  ScanSummary,
  Workspace,
  WorkspaceSettings,
} from '../models/graph.models';

/**
 * Workspaces and scan lifecycle (FR-1, FR-7), as signals (§2: no NgRx).
 *
 * Scan progress arrives over SSE and falls back to polling if the stream cannot be opened —
 * FR-7.1 allows either, and having both means a proxy that buffers event streams degrades to
 * something that still works rather than to a progress screen that never moves.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly api = inject(ServiceAtlasApi);

  private readonly workspacesSignal = signal<Workspace[]>([]);
  private readonly selectedIdSignal = signal<number | null>(null);
  private readonly loadingSignal = signal(false);
  private readonly errorSignal = signal<string | null>(null);

  private readonly activeScanSignal = signal<ScanSummary | null>(null);
  private readonly repoProgressSignal = signal<RepoProgress[]>([]);
  private readonly scanningSignal = signal(false);

  private eventSource: EventSource | null = null;
  private pollHandle: ReturnType<typeof setInterval> | null = null;

  readonly workspaces = this.workspacesSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly activeScan = this.activeScanSignal.asReadonly();
  readonly repoProgress = this.repoProgressSignal.asReadonly();
  readonly scanning = this.scanningSignal.asReadonly();

  readonly selected = computed(() => {
    const id = this.selectedIdSignal();
    return id === null ? null : (this.workspacesSignal().find((w) => w.id === id) ?? null);
  });

  readonly hasWorkspaces = computed(() => this.workspacesSignal().length > 0);

  /** Repositories that finished, out of those discovered — drives the progress bar (UX-3). */
  readonly scanProgress = computed(() => {
    const repos = this.repoProgressSignal();
    if (repos.length === 0) {
      return { done: 0, total: this.activeScanSignal()?.repoCount ?? 0, failed: 0 };
    }
    return {
      done: repos.filter((r) => r.status === 'DONE' || r.status === 'UNCHANGED').length,
      total: repos.length,
      failed: repos.filter((r) => r.status === 'FAILED').length,
    };
  });

  async load(): Promise<void> {
    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    try {
      const workspaces = await firstValueFrom(this.api.listWorkspaces());
      this.workspacesSignal.set(workspaces);
      if (this.selectedIdSignal() === null && workspaces.length > 0) {
        this.selectedIdSignal.set(workspaces[0].id);
      }
    } catch (problem) {
      this.errorSignal.set(problemMessage(problem));
    } finally {
      this.loadingSignal.set(false);
    }
  }

  select(id: number | null): void {
    this.selectedIdSignal.set(id);
    this.stopWatching();
    this.repoProgressSignal.set([]);
    this.activeScanSignal.set(null);
  }

  async preview(rootPath: string, settings?: WorkspaceSettings): Promise<RootPathPreview> {
    return firstValueFrom(this.api.previewRootPath(rootPath, settings));
  }

  async create(name: string, rootPath: string, settings?: WorkspaceSettings): Promise<Workspace> {
    const created = await firstValueFrom(this.api.createWorkspace(name, rootPath, settings));
    this.workspacesSignal.update((all) => [...all, created]);
    this.selectedIdSignal.set(created.id);
    return created;
  }

  async remove(id: number): Promise<void> {
    await firstValueFrom(this.api.deleteWorkspace(id));
    this.workspacesSignal.update((all) => all.filter((w) => w.id !== id));
    if (this.selectedIdSignal() === id) {
      this.select(this.workspacesSignal()[0]?.id ?? null);
    }
  }

  /** Starts a scan and follows it to completion. Resolves when the scan reaches a terminal state. */
  async startScan(workspaceId: number): Promise<ScanSummary> {
    this.errorSignal.set(null);
    this.repoProgressSignal.set([]);
    this.scanningSignal.set(true);
    try {
      const scan = await firstValueFrom(this.api.startScan(workspaceId));
      this.activeScanSignal.set(scan);
      this.watch(workspaceId, scan.id);
      return scan;
    } catch (problem) {
      this.scanningSignal.set(false);
      this.errorSignal.set(problemMessage(problem));
      throw problem;
    }
  }

  /** Subscribes to the live stream, with polling as the fallback (FR-7.1). */
  private watch(workspaceId: number, scanId: number): void {
    this.stopWatching();

    const refresh = () => void this.refreshScan(workspaceId, scanId);

    try {
      const source = new EventSource(this.api.scanEventsUrl(workspaceId, scanId));
      this.eventSource = source;
      source.addEventListener('repo', refresh);
      source.addEventListener('scan', refresh);
      source.addEventListener('discovered', refresh);
      source.onerror = () => {
        // The stream closes normally when the scan ends; a final poll settles the true state.
        this.closeEventSource();
        refresh();
      };
    } catch {
      // No EventSource (or it was refused): polling alone is enough.
    }

    this.pollHandle = setInterval(refresh, 1200);
    refresh();
  }

  private async refreshScan(workspaceId: number, scanId: number): Promise<void> {
    let response: ScanStatusResponse;
    try {
      response = await firstValueFrom(this.api.getScan(workspaceId, scanId));
    } catch (problem) {
      this.errorSignal.set(problemMessage(problem));
      this.finishScan();
      return;
    }

    this.activeScanSignal.set(response.scan);
    this.repoProgressSignal.set(response.repos);

    if (response.scan.status !== 'PENDING' && response.scan.status !== 'RUNNING') {
      this.finishScan();
      void this.load();
    }
  }

  private finishScan(): void {
    this.scanningSignal.set(false);
    this.stopWatching();
  }

  private stopWatching(): void {
    this.closeEventSource();
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }

  private closeEventSource(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
}
