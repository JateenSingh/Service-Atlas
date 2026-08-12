import { Injectable } from '@angular/core';
import { LayoutResult } from '../../../core/models/graph.models';
import { LayoutEngine, LayoutRequest } from './layout-engine';

interface PendingLayout {
  resolve: (result: LayoutResult) => void;
  reject: (error: Error) => void;
}

/**
 * {@link LayoutEngine} backed by ELK running in a Web Worker (ADR-001).
 *
 * <p>Superseded requests are cancelled rather than queued: while the user drags a filter slider we
 * only care about the newest layout, and resolving stale ones would make the canvas jump backwards.
 *
 * <p>If the environment has no Worker — an old browser, a locked-down embed, a unit test — the
 * engine falls back to importing ELK on the main thread. Slower, but the app still draws.
 */
@Injectable({ providedIn: 'root' })
export class ElkLayoutEngine implements LayoutEngine {
  readonly id = 'elk-layered';

  private worker: Worker | null = null;
  private nextRequestId = 1;
  private readonly pending = new Map<number, PendingLayout>();
  private latestRequestId = 0;
  private mainThreadElk: Promise<typeof import('./elk-main-thread')> | null = null;

  layout(request: LayoutRequest): Promise<LayoutResult> {
    if (request.nodes.length === 0) {
      return Promise.resolve({ positions: {}, routes: {}, width: 0, height: 0 });
    }

    const worker = this.ensureWorker();
    if (!worker) {
      return this.layoutOnMainThread(request);
    }

    const id = this.nextRequestId++;
    this.latestRequestId = id;

    return new Promise<LayoutResult>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      worker.postMessage({ id, request });
    })
      .then((result) => this.applyPinnedPositions(result, request))
      .catch(() => {
        // A worker that cannot start or run is not a reason to show an empty canvas — fall back to
        // the main thread and disable the worker for the rest of the session.
        this.worker?.terminate();
        this.worker = null;
        this.workerUsable = false;
        return this.layoutOnMainThread(request);
      });
  }

  dispose(): void {
    this.worker?.terminate();
    this.worker = null;
    this.pending.clear();
  }

  private workerUsable = true;

  private ensureWorker(): Worker | null {
    if (this.worker) {
      return this.worker;
    }
    if (!this.workerUsable || typeof Worker === 'undefined') {
      return null;
    }
    try {
      const worker = new Worker(new URL('./layout.worker', import.meta.url), { type: 'module' });
      worker.onmessage = ({ data }: MessageEvent<WorkerResponse>) => this.settle(data);
      worker.onerror = () => this.rejectAll(new Error('The layout worker stopped unexpectedly.'));
      this.worker = worker;
      return worker;
    } catch {
      return null;
    }
  }

  private settle(data: WorkerResponse): void {
    const pending = this.pending.get(data.id);
    if (!pending) {
      return;
    }
    this.pending.delete(data.id);

    if (data.id !== this.latestRequestId) {
      // Superseded: never resolve, or the canvas would snap back to an older arrangement.
      return;
    }
    if (data.error) {
      pending.reject(new Error(data.error));
      return;
    }
    if (data.result) {
      pending.resolve(data.result);
    }
  }

  private rejectAll(error: Error): void {
    for (const pending of this.pending.values()) {
      pending.reject(error);
    }
    this.pending.clear();
    this.worker = null;
  }

  private async layoutOnMainThread(request: LayoutRequest): Promise<LayoutResult> {
    this.mainThreadElk ??= import('./elk-main-thread');
    const module = await this.mainThreadElk;
    return this.applyPinnedPositions(await module.layoutWithElk(request), request);
  }

  /**
   * User-dragged positions win over the engine (FR-5.3, FR-4.4). Applied as a post-pass so a pinned
   * node never has to fight the layout algorithm for its coordinates.
   */
  private applyPinnedPositions(result: LayoutResult, request: LayoutRequest): LayoutResult {
    const pinned = request.pinned;
    if (!pinned || Object.keys(pinned).length === 0) {
      return result;
    }
    const positions = { ...result.positions };
    for (const [key, position] of Object.entries(pinned)) {
      const existing = positions[key];
      if (existing) {
        positions[key] = { ...existing, x: position.x, y: position.y };
      }
    }
    return { ...result, positions };
  }
}

interface WorkerResponse {
  id: number;
  result?: LayoutResult;
  error?: string;
}
