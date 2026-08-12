import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import {
  GraphResponse,
  ProblemDetail,
  RootPathPreview,
  ScanStatusResponse,
  ScanSummary,
  Workspace,
  WorkspaceDetail,
  WorkspaceSettings,
} from '../models/graph.models';

/** Base path — the API is served from the same origin as the app (§2 packaging). */
export const API_BASE = '/api/v1';

/**
 * The single HTTP boundary between the app and the backend.
 *
 * One service rather than four: the API is small, and splitting it by resource would scatter the
 * error-normalisation logic that every caller depends on.
 */
@Injectable({ providedIn: 'root' })
export class ServiceAtlasApi {
  private readonly http = inject(HttpClient);

  // ---------------------------------------------------------------- workspaces

  listWorkspaces(): Observable<Workspace[]> {
    return this.get<Workspace[]>(`${API_BASE}/workspaces`);
  }

  getWorkspace(id: number): Observable<WorkspaceDetail> {
    return this.get<WorkspaceDetail>(`${API_BASE}/workspaces/${id}`);
  }

  createWorkspace(
    name: string,
    rootPath: string,
    settings?: WorkspaceSettings,
  ): Observable<Workspace> {
    return this.post<Workspace>(`${API_BASE}/workspaces`, { name, rootPath, settings });
  }

  updateWorkspace(id: number, changes: Partial<Workspace>): Observable<Workspace> {
    return this.http
      .patch<Workspace>(`${API_BASE}/workspaces/${id}`, changes)
      .pipe(catchError(toProblem));
  }

  deleteWorkspace(id: number): Observable<void> {
    return this.http.delete<void>(`${API_BASE}/workspaces/${id}`).pipe(catchError(toProblem));
  }

  /** FR-1.2 — validate a folder and count its repositories before committing to it. */
  previewRootPath(rootPath: string, settings?: WorkspaceSettings): Observable<RootPathPreview> {
    return this.post<RootPathPreview>(`${API_BASE}/workspaces/preview`, { rootPath, settings });
  }

  // ---------------------------------------------------------------- scans

  startScan(workspaceId: number): Observable<ScanSummary> {
    return this.post<ScanSummary>(`${API_BASE}/workspaces/${workspaceId}/scans`, {});
  }

  getScan(workspaceId: number, scanId: number): Observable<ScanStatusResponse> {
    return this.get<ScanStatusResponse>(`${API_BASE}/workspaces/${workspaceId}/scans/${scanId}`);
  }

  listScans(workspaceId: number): Observable<ScanSummary[]> {
    return this.get<ScanSummary[]>(`${API_BASE}/workspaces/${workspaceId}/scans`);
  }

  /** URL of the SSE progress stream (FR-7.1); consumed via EventSource, not HttpClient. */
  scanEventsUrl(workspaceId: number, scanId: number): string {
    return `${API_BASE}/workspaces/${workspaceId}/scans/${scanId}/events`;
  }

  // ---------------------------------------------------------------- graph

  getGraph(workspaceId: number): Observable<GraphResponse> {
    return this.get<GraphResponse>(`${API_BASE}/workspaces/${workspaceId}/graph`);
  }

  private get<T>(url: string): Observable<T> {
    return this.http.get<T>(url).pipe(catchError(toProblem));
  }

  private post<T>(url: string, body: unknown): Observable<T> {
    return this.http.post<T>(url, body).pipe(catchError(toProblem));
  }
}

/**
 * Normalises every failure into a {@link ProblemDetail}, so components can show the server's own
 * explanation ("Path does not exist: /foo") instead of "Http failure response for /api/v1/...".
 */
export function toProblem(error: HttpErrorResponse): Observable<never> {
  const problem: ProblemDetail =
    error.error && typeof error.error === 'object' && 'detail' in error.error
      ? (error.error as ProblemDetail)
      : {
          title: error.status === 0 ? 'Cannot reach Service Atlas' : 'Request failed',
          status: error.status,
          detail:
            error.status === 0
              ? 'The backend is not responding. Is it still running?'
              : error.message,
        };
  return throwError(() => problem);
}

export function problemMessage(problem: unknown): string {
  if (problem && typeof problem === 'object') {
    const detail = (problem as ProblemDetail).detail ?? (problem as ProblemDetail).title;
    if (detail) {
      return detail;
    }
  }
  return 'Something went wrong.';
}
