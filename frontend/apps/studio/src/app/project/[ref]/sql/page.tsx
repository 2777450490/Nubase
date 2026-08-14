'use client';

import dynamic from 'next/dynamic';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Play, Save, History, FileCode, Trash2, RefreshCw, CheckCircle2, XCircle } from 'lucide-react';
import {
  Button,
  Badge,
  Input,
  Label,
  Dialog,
  DialogHeader,
  DialogBody,
  DialogFooter,
  cn,
} from '@nubase/ui';
import { apiFetch, type ApiError } from '@/lib/api';
import { useSession, isProjectReady } from '@/lib/session';
import { NotProvisioned } from '@/components/not-provisioned';
import { useProjectRef } from '@/lib/route-params';
import { useI18n } from '@/lib/i18n';

const MonacoEditor = dynamic(() => import('@monaco-editor/react'), {
  ssr: false,
  loading: () => <EditorLoading />,
});

function EditorLoading() {
  const { tr } = useI18n();
  return (
    <div className="flex h-full items-center justify-center text-xs text-muted-foreground">
      {tr('sql.loadingEditor')}
    </div>
  );
}

interface SqlStatementResult {
  index: number;
  type: 'query' | 'update';
  rows?: Array<Record<string, unknown>>;
  rows_affected?: number;
  error?: string;
}

interface SqlExecutionResponse {
  success: boolean;
  results?: SqlStatementResult[];
  rows_affected?: number;
  execution_time_ms?: number;
  error?: string;
}

interface SqlSnippet {
  id: number;
  name: string;
  query: string;
  created_at?: string;
  updated_at?: string;
}

interface HistoryEntry {
  id: number;
  query: string;
  success: boolean;
  execution_time_ms?: number;
  error_message?: string | null;
  created_at?: string;
}

export default function SqlEditorPage({ params }: { params: { ref: string } }) {
  const { project } = useSession();
  const projectRef = useProjectRef(params.ref);
  const ready = isProjectReady(project);
  if (!ready) {
    return <NotProvisioned projectRef={project?.ref ?? projectRef} initStatus={project?.initStatus} />;
  }
  return <SqlEditorInner />;
}

function SqlEditorInner() {
  const { tr } = useI18n();
  const { project, platformKey } = useSession();
  const apikey = project!.apikey;
  const projectRef = project!.ref;

  const [sql, setSql] = useState(`${tr('sql.initialPlaceholder')}\nselect now();`);
  const [running, setRunning] = useState(false);
  const [response, setResponse] = useState<SqlExecutionResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [tab, setTab] = useState<'snippets' | 'history'>('snippets');
  const [snippets, setSnippets] = useState<SqlSnippet[]>([]);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [activeSnippetId, setActiveSnippetId] = useState<number | null>(null);
  const [savingOpen, setSavingOpen] = useState(false);

  const lastQuery = useMemo<SqlStatementResult | null>(() => {
    if (!response?.results) return null;
    for (let i = response.results.length - 1; i >= 0; i -= 1) {
      const r = response.results[i];
      if (r?.type === 'query' && r.rows) return r;
    }
    return null;
  }, [response]);

  const loadSnippets = useCallback(async () => {
    if (!platformKey) return;
    try {
      const res = await apiFetch<SqlSnippet[]>(
        `/auth/v1/admin/projects/${encodeURIComponent(projectRef)}/snippets`,
        { apikey: platformKey }
      );
      setSnippets(res);
    } catch {
      // ignore — sidebar best-effort
    }
  }, [platformKey, projectRef]);

  const loadHistory = useCallback(async () => {
    if (!platformKey) return;
    try {
      const res = await apiFetch<HistoryEntry[]>(
        `/auth/v1/admin/projects/${encodeURIComponent(projectRef)}/sql/history?limit=50`,
        { apikey: platformKey }
      );
      setHistory(res);
    } catch {
      // ignore
    }
  }, [platformKey, projectRef]);

  useEffect(() => {
    loadSnippets();
    loadHistory();
  }, [loadSnippets, loadHistory]);

  async function run() {
    setRunning(true);
    setError(null);
    try {
      const res = await apiFetch<SqlExecutionResponse>('/auth/v1/admin/sql/execute', {
        method: 'POST',
        body: { query: sql },
        apikey,
      });
      setResponse(res);
      if (!res.success && res.error) setError(res.error);
      // Refresh history afterward — each execution lands in sql_execution_records.
      loadHistory();
    } catch (err) {
      setError((err as ApiError).message ?? tr('sql.queryFailed'));
      setResponse(null);
    } finally {
      setRunning(false);
    }
  }

  async function deleteSnippet(id: number) {
    if (!platformKey) return;
    if (!confirm(tr('sql.deleteConfirm'))) return;
    try {
      await apiFetch(
        `/auth/v1/admin/projects/${encodeURIComponent(projectRef)}/snippets/${id}`,
        { method: 'DELETE', apikey: platformKey }
      );
      if (activeSnippetId === id) setActiveSnippetId(null);
      await loadSnippets();
    } catch (err) {
      setError((err as ApiError).message ?? tr('sql.deleteFailed'));
    }
  }

  const columns = lastQuery?.rows?.[0] ? Object.keys(lastQuery.rows[0]) : [];

  return (
    <div className="flex h-full">
      <aside className="flex w-64 flex-col border-r border-border">
        <div className="flex items-center border-b border-border">
          <button
            onClick={() => setTab('snippets')}
            className={cn(
              'flex-1 px-3 py-2 text-xs font-medium uppercase tracking-wide',
              tab === 'snippets' ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            <FileCode className="mr-1 inline h-3.5 w-3.5" /> {tr('sql.tabSnippets')}
          </button>
          <button
            onClick={() => setTab('history')}
            className={cn(
              'flex-1 border-l border-border px-3 py-2 text-xs font-medium uppercase tracking-wide',
              tab === 'history' ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            <History className="mr-1 inline h-3.5 w-3.5" /> {tr('sql.tabHistory')}
          </button>
        </div>
        <div className="flex items-center justify-between border-b border-border px-3 py-1.5 text-[10px] text-muted-foreground">
          <span>{tab === 'snippets' ? tr('sql.savedCount', { n: snippets.length }) : tr('sql.recentCount', { n: history.length })}</span>
          <button
            onClick={() => (tab === 'snippets' ? loadSnippets() : loadHistory())}
            className="rounded p-0.5 hover:bg-accent"
            aria-label={tr('sql.refreshAria')}
          >
            <RefreshCw className="h-3 w-3" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto">
          {tab === 'snippets' ? (
            snippets.length === 0 ? (
              <p className="p-3 text-xs text-muted-foreground">
                {tr('sql.noSnippetsPrefix')} <strong>{tr('sql.save')}</strong> {tr('sql.noSnippetsSuffix')}
              </p>
            ) : (
              <ul>
                {snippets.map((s) => (
                  <li
                    key={s.id}
                    className={cn(
                      'group flex items-start justify-between gap-1 border-b border-border/40 px-3 py-2 hover:bg-accent/30',
                      activeSnippetId === s.id && 'bg-accent/40'
                    )}
                  >
                    <button
                      onClick={() => {
                        setSql(s.query);
                        setActiveSnippetId(s.id);
                      }}
                      className="min-w-0 flex-1 text-left"
                    >
                      <p className="truncate text-sm font-medium">{s.name}</p>
                      <p className="truncate font-mono text-[10px] text-muted-foreground">
                        {s.query.split('\n')[0]}
                      </p>
                    </button>
                    <button
                      onClick={() => deleteSnippet(s.id)}
                      className="rounded p-1 text-muted-foreground opacity-0 hover:bg-destructive/15 hover:text-destructive group-hover:opacity-100"
                      aria-label={tr('sql.deleteSnippetAria')}
                    >
                      <Trash2 className="h-3 w-3" />
                    </button>
                  </li>
                ))}
              </ul>
            )
          ) : history.length === 0 ? (
            <p className="p-3 text-xs text-muted-foreground">
              {tr('sql.noHistory')}
            </p>
          ) : (
            <ul>
              {history.map((h) => (
                <li key={h.id} className="border-b border-border/40 px-3 py-2 hover:bg-accent/30">
                  <button
                    onClick={() => setSql(h.query)}
                    className="block w-full text-left"
                  >
                    <div className="flex items-center gap-1.5">
                      {h.success ? (
                        <CheckCircle2 className="h-3 w-3 shrink-0 text-emerald-400" />
                      ) : (
                        <XCircle className="h-3 w-3 shrink-0 text-destructive" />
                      )}
                      <span className="truncate font-mono text-[11px]">
                        {h.query.split('\n')[0]}
                      </span>
                    </div>
                    <div className="mt-0.5 flex justify-between text-[10px] text-muted-foreground">
                      <span>{formatDate(h.created_at)}</span>
                      <span>{h.execution_time_ms ?? 0} ms</span>
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>

      <section className="flex flex-1 flex-col overflow-hidden">
        <header className="flex items-center justify-between border-b border-border px-4 py-2">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold">{tr('sql.title')}</h2>
            {response ? (
              <Badge variant="outline">
                {tr('sql.resultBadge', {
                  rows: lastQuery?.rows?.length ?? response.rows_affected ?? 0,
                  ms: response.execution_time_ms ?? 0,
                })}
              </Badge>
            ) : null}
          </div>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" onClick={() => setSavingOpen(true)}>
              <Save className="h-3.5 w-3.5" /> {tr('sql.save')}
            </Button>
            <Button size="sm" onClick={run} disabled={running}>
              <Play className="h-3.5 w-3.5" /> {running ? tr('sql.running') : tr('sql.run')}
            </Button>
          </div>
        </header>

        <div className="flex flex-1 flex-col overflow-hidden">
          <div className="h-1/2 border-b border-border">
            <MonacoEditor
              height="100%"
              defaultLanguage="sql"
              theme="vs-dark"
              value={sql}
              onChange={(v) => {
                setSql(v ?? '');
                if (activeSnippetId !== null) setActiveSnippetId(null);
              }}
              options={{
                fontSize: 13,
                minimap: { enabled: false },
                scrollBeyondLastLine: false,
                fontFamily: 'var(--font-mono)',
                padding: { top: 12 },
              }}
            />
          </div>

          <div className="flex-1 overflow-auto bg-background">
            {error ? (
              <pre className="m-4 whitespace-pre-wrap rounded-md border border-destructive/30 bg-destructive/10 p-3 text-xs text-destructive">
                {error}
              </pre>
            ) : !response ? (
              <p className="p-4 text-sm text-muted-foreground">{tr('sql.runHint')}</p>
            ) : lastQuery && lastQuery.rows && lastQuery.rows.length > 0 ? (
              <table className="w-full border-collapse text-sm">
                <thead className="sticky top-0 bg-card">
                  <tr className="border-b border-border">
                    {columns.map((c) => (
                      <th key={c} className="px-3 py-2 text-left font-medium">
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {lastQuery.rows.map((row, i) => (
                    <tr key={i} className="border-b border-border/50">
                      {columns.map((c) => (
                        <td key={c} className="px-3 py-2 font-mono text-xs">
                          {String(row[c] ?? '')}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <div className="space-y-2 p-4 text-sm">
                <p className="text-emerald-400">
                  {tr('sql.executed', { ms: response.execution_time_ms ?? 0 })}
                </p>
                {response.results?.map((r) => (
                  <p key={r.index} className="text-muted-foreground">
                    {tr('sql.statementResult', { index: r.index, type: r.type, rows: r.rows_affected ?? r.rows?.length ?? 0 })}
                  </p>
                ))}
              </div>
            )}
          </div>
        </div>
      </section>

      <SaveSnippetDialog
        open={savingOpen}
        projectRef={projectRef}
        query={sql}
        onClose={() => setSavingOpen(false)}
        onSaved={() => {
          setSavingOpen(false);
          loadSnippets();
        }}
      />
    </div>
  );
}

function SaveSnippetDialog({
  open,
  projectRef,
  query,
  onClose,
  onSaved,
}: {
  open: boolean;
  projectRef: string;
  query: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { tr } = useI18n();
  const { platformKey } = useSession();
  const [name, setName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setName('');
      setError(null);
    }
  }, [open]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!platformKey) return;
    setSubmitting(true);
    setError(null);
    try {
      await apiFetch(`/auth/v1/admin/projects/${encodeURIComponent(projectRef)}/snippets`, {
        method: 'POST',
        body: { name: name.trim(), query },
        apikey: platformKey,
      });
      onSaved();
    } catch (err) {
      setError((err as ApiError).message ?? tr('sql.saveFailed'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose}>
      <DialogHeader title={tr('sql.dialogTitle')} description={tr('sql.dialogDesc')} onClose={onClose} />
      <form onSubmit={submit}>
        <DialogBody className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="snippet-name">{tr('sql.fieldName')}</Label>
            <Input
              id="snippet-name"
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={tr('sql.snippetPlaceholder')}
              autoFocus
            />
          </div>
          {error ? <p className="text-xs text-destructive">{error}</p> : null}
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={submitting}>
            {tr('sql.cancel')}
          </Button>
          <Button type="submit" disabled={submitting || !name.trim()}>
            {submitting ? tr('sql.saving') : tr('sql.save')}
          </Button>
        </DialogFooter>
      </form>
    </Dialog>
  );
}

function formatDate(s?: string | null): string {
  if (!s) return '—';
  try {
    return new Date(s).toLocaleString();
  } catch {
    return s;
  }
}
