import { LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { RepoProgress } from '../../core/models/graph.models';

/**
 * Live per-repository scan progress (FR-7.1, UX-3).
 *
 * Failed repositories are listed with their error rather than hidden: a repo that could not be
 * parsed is information about the estate, and it still appears on the canvas with a warning badge
 * (FR-7.2).
 */
@Component({
  selector: 'sa-scan-progress',
  standalone: true,
  imports: [LowerCasePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './scan-progress.component.html',
  styleUrl: './scan-progress.component.css',
})
export class ScanProgressComponent {
  private readonly store = inject(WorkspaceStore);

  readonly scan = this.store.activeScan;
  readonly repos = this.store.repoProgress;
  readonly scanning = this.store.scanning;
  readonly progress = this.store.scanProgress;

  readonly percent = computed(() => {
    const { done, total } = this.progress();
    return total === 0 ? 0 : Math.round((done / total) * 100);
  });

  readonly sortedRepos = computed(() =>
    [...this.repos()].sort((a, b) => statusRank(a) - statusRank(b) || a.repoPath.localeCompare(b.repoPath)),
  );

  readonly failures = computed(() => this.repos().filter((repo) => repo.status === 'FAILED'));

  statusLabel(repo: RepoProgress): string {
    switch (repo.status) {
      case 'DISCOVERED':
        return 'queued';
      case 'PARSING':
        return 'parsing';
      case 'DONE':
        return repo.durationMs !== null ? `${repo.durationMs} ms` : 'done';
      case 'UNCHANGED':
        return 'unchanged';
      case 'FAILED':
        return 'failed';
      default:
        return 'skipped';
    }
  }
}

/** Failures first, then work in flight, then everything finished. */
function statusRank(repo: RepoProgress): number {
  switch (repo.status) {
    case 'FAILED':
      return 0;
    case 'PARSING':
      return 1;
    case 'DISCOVERED':
      return 2;
    default:
      return 3;
  }
}
