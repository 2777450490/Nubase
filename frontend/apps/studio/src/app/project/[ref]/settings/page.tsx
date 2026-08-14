'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Copy, Eye, EyeOff, Pause, Play, Trash2 } from 'lucide-react';
import {
  Button,
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  Badge,
  Input,
  Label,
} from '@nubase/ui';
import { useSession } from '@/lib/session';
import { apiFetch, fetchAllProjects, API_BASE, type ApiError } from '@/lib/api';
import { MembersCard } from '@/components/members-card';
import { useProjectRef } from '@/lib/route-params';
import { useI18n } from '@/lib/i18n';

interface ProjectKeysResponse {
  service_role_token?: string | null;
  authenticated_token?: string | null;
}

interface ProjectSummaryPatch {
  ref: string;
  name?: string | null;
  description?: string | null;
  initStatus?: string | null;
  healthStatus?: string | null;
  enabled?: boolean;
  apikey?: string;
}

export default function SettingsPage({ params }: { params: { ref: string } }) {
  const { tr } = useI18n();
  const router = useRouter();
  const { project, platformKey, setProject } = useSession();
  const projectRef = useProjectRef(params.ref);
  const [copied, setCopied] = useState<string | null>(null);
  const [showService, setShowService] = useState(false);
  const [showAuth, setShowAuth] = useState(false);

  const [name, setName] = useState(project?.name ?? projectRef);
  const [description, setDescription] = useState('');
  const [savingMeta, setSavingMeta] = useState(false);
  const [metaError, setMetaError] = useState<string | null>(null);
  const [metaSaved, setMetaSaved] = useState(false);

  const [keys, setKeys] = useState<ProjectKeysResponse | null>(null);
  const [paused, setPaused] = useState(false);
  const [busy, setBusy] = useState<null | 'pause' | 'delete'>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const loadKeys = useCallback(async () => {
    if (!platformKey || !projectRef) return;
    try {
      const res = await apiFetch<ProjectKeysResponse>(
        `/auth/v1/admin/projects/${encodeURIComponent(projectRef)}/keys`,
        { apikey: platformKey }
      );
      setKeys(res);
    } catch {
      // best-effort; falls back to session.project.apikey for service_role
    }
  }, [platformKey, projectRef]);

  useEffect(() => {
    setName(project?.name ?? projectRef);
  }, [project?.name, projectRef]);

  useEffect(() => {
    loadKeys();
  }, [loadKeys]);

  // Initialise the pause/resume state from the project's real `enabled` flag, otherwise the toggle
  // starts wrong (always "Pause") and can't resume an already-paused project.
  useEffect(() => {
    if (!platformKey || !projectRef) return;
    let cancelled = false;
    fetchAllProjects<{ ref: string; enabled: boolean }>(platformKey)
      .then((list) => {
        if (cancelled) return;
        const me = list.find((p) => p.ref === projectRef);
        if (me) setPaused(!me.enabled);
      })
      .catch(() => {
        /* leave the default; the toggle still sends an explicit enabled value */
      });
    return () => {
      cancelled = true;
    };
  }, [platformKey, projectRef]);

  async function copy(label: string, value: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(label);
      setTimeout(() => setCopied(null), 1500);
    } catch {
      // ignore
    }
  }

  async function saveMeta(e: React.FormEvent) {
    e.preventDefault();
    if (!platformKey) return;
    setSavingMeta(true);
    setMetaError(null);
    setMetaSaved(false);
    try {
      const patch: Record<string, unknown> = {};
      if (name.trim() && name.trim() !== (project?.name ?? projectRef)) patch.appName = name.trim();
      if (description.trim()) patch.description = description.trim();
      if (Object.keys(patch).length === 0) {
        setSavingMeta(false);
        return;
      }
      const res = await apiFetch<ProjectSummaryPatch>(
        `/auth/v1/admin/projects/${encodeURIComponent(projectRef)}`,
        { method: 'PATCH', body: patch, apikey: platformKey }
      );
      if (project && project.ref === projectRef) {
        setProject({ ...project, name: res.name ?? project.name });
      }
      setMetaSaved(true);
      setTimeout(() => setMetaSaved(false), 1500);
    } catch (err) {
      setMetaError(parseError(err as ApiError) ?? tr('settings.saveChanges'));
    } finally {
      setSavingMeta(false);
    }
  }

  async function togglePause() {
    if (!platformKey) return;
    setBusy('pause');
    setActionError(null);
    try {
      await apiFetch(`/auth/v1/admin/projects/${encodeURIComponent(projectRef)}`, {
        method: 'PATCH',
        body: { enabled: paused },
        apikey: platformKey,
      });
      setPaused((p) => !p);
    } catch (err) {
      setActionError(parseError(err as ApiError) ?? tr('settings.cancel'));
    } finally {
      setBusy(null);
    }
  }

  async function doDelete() {
    if (!platformKey) return;
    setBusy('delete');
    setActionError(null);
    try {
      await apiFetch(`/auth/v1/admin/projects/${encodeURIComponent(projectRef)}`, {
        method: 'DELETE',
        apikey: platformKey,
      });
      // Session still references this project; clear it to avoid stale UI.
      if (project?.ref === projectRef) setProject(null);
      router.replace('/projects');
    } catch (err) {
      setActionError(parseError(err as ApiError) ?? tr('settings.delete'));
      setBusy(null);
    }
  }

  const serviceKey = keys?.service_role_token ?? project?.apikey ?? '';
  const authKey = keys?.authenticated_token ?? '';

  return (
    <div className="space-y-6 overflow-auto p-8">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">{tr('settings.title')}</h1>
        <p className="text-sm text-muted-foreground">{tr('settings.subtitle')}</p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tr('settings.general')}</CardTitle>
          <CardDescription>{tr('settings.generalDesc')}</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={saveMeta} className="space-y-3 text-sm">
            <Row label={tr('settings.reference')}>
              <code className="font-mono text-xs">{projectRef}</code>
              <p className="mt-1 text-xs text-muted-foreground">{tr('settings.referenceHelp')}</p>
            </Row>
            <div className="grid grid-cols-[140px_1fr] items-start gap-4">
              <Label htmlFor="proj-name" className="pt-2 text-xs text-muted-foreground">
                {tr('settings.displayName')}
              </Label>
              <Input id="proj-name" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="grid grid-cols-[140px_1fr] items-start gap-4">
              <Label htmlFor="proj-desc" className="pt-2 text-xs text-muted-foreground">
                {tr('settings.description')}
              </Label>
              <Input
                id="proj-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={tr('settings.optional')}
              />
            </div>
            {metaError ? <p className="text-xs text-destructive">{metaError}</p> : null}
            <div className="flex items-center justify-end gap-2">
              {metaSaved ? <Badge variant="success">{tr('settings.saved')}</Badge> : null}
              <Button type="submit" size="sm" disabled={savingMeta}>
                {savingMeta ? tr('settings.saving') : tr('settings.saveChanges')}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{tr('settings.api')}</CardTitle>
          <CardDescription>{tr('settings.apiDesc')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4 text-sm">
          <Row label={tr('settings.url')}>
            <div className="flex items-center gap-2">
              <code className="font-mono text-xs">{API_BASE}</code>
              <Button size="icon" variant="ghost" onClick={() => copy('url', API_BASE)} aria-label={tr('settings.copyUrl')}>
                <Copy className="h-3.5 w-3.5" />
              </Button>
              {copied === 'url' ? <Badge variant="success">{tr('settings.copied')}</Badge> : null}
            </div>
          </Row>
          <KeyRow
            label={tr('settings.serviceRole')}
            description={tr('settings.serviceRoleDesc')}
            value={serviceKey}
            show={showService}
            onToggle={() => setShowService((v) => !v)}
            onCopy={() => copy('service', serviceKey)}
            copied={copied === 'service'}
          />
          <KeyRow
            label={tr('settings.authenticated')}
            description={tr('settings.authenticatedDesc')}
            value={authKey}
            show={showAuth}
            onToggle={() => setShowAuth((v) => !v)}
            onCopy={() => copy('auth', authKey)}
            copied={copied === 'auth'}
          />
        </CardContent>
      </Card>

      <MembersCard projectRef={projectRef} />

      <Card>
        <CardHeader>
          <CardTitle className="text-base text-destructive">{tr('settings.dangerZone')}</CardTitle>
          <CardDescription>{tr('settings.dangerDesc')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {actionError ? <p className="text-xs text-destructive">{actionError}</p> : null}
          <div className="flex items-center justify-between gap-4 rounded-md border border-destructive/30 p-3">
            <div>
              <p className="text-sm font-medium">{paused ? tr('settings.resume') : tr('settings.pause')}</p>
              <p className="text-xs text-muted-foreground">
                {paused
                  ? tr('settings.resumeDesc')
                  : tr('settings.pauseDesc')}
              </p>
            </div>
            <Button size="sm" variant="outline" onClick={togglePause} disabled={busy === 'pause'}>
              {paused ? (
                <>
                  <Play className="h-3.5 w-3.5" /> {tr('settings.resumeBtn')}
                </>
              ) : (
                <>
                  <Pause className="h-3.5 w-3.5" /> {tr('settings.pauseBtn')}
                </>
              )}
            </Button>
          </div>
          <div className="flex items-center justify-between gap-4 rounded-md border border-destructive/30 p-3">
            <div>
              <p className="text-sm font-medium">{tr('settings.deleteProject')}</p>
              <p className="text-xs text-muted-foreground">
                {tr('settings.deleteDesc')}
              </p>
            </div>
            {confirmDelete ? (
              <div className="flex items-center gap-1">
                <Button size="sm" variant="outline" onClick={() => setConfirmDelete(false)} disabled={busy === 'delete'}>
                  {tr('settings.cancel')}
                </Button>
                <Button size="sm" variant="destructive" onClick={doDelete} disabled={busy === 'delete'}>
                  {busy === 'delete' ? tr('settings.deleting') : tr('settings.confirmDelete')}
                </Button>
              </div>
            ) : (
              <Button size="sm" variant="destructive" onClick={() => setConfirmDelete(true)}>
                <Trash2 className="h-3.5 w-3.5" /> {tr('settings.delete')}
              </Button>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

/** Backend errors are JSON ({ error, message }); unwrap to a readable string instead of raw JSON. */
function parseError(err: ApiError): string | null {
  try {
    const parsed = JSON.parse(err.message);
    return parsed?.message ?? parsed?.error ?? null;
  } catch {
    return err.message;
  }
}

function KeyRow({
  label,
  description,
  value,
  show,
  onToggle,
  onCopy,
  copied,
}: {
  label: string;
  description: React.ReactNode;
  value: string;
  show: boolean;
  onToggle: () => void;
  onCopy: () => void;
  copied: boolean;
}) {
  const { tr } = useI18n();
  const masked = value ? `${value.slice(0, 14)}…${value.slice(-8)}` : '—';
  return (
    <div className="grid grid-cols-[140px_1fr] items-start gap-4">
      <span className="pt-1 text-xs text-muted-foreground">{label}</span>
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <code className="break-all font-mono text-xs">{show ? value || '—' : masked}</code>
          <Button size="icon" variant="ghost" onClick={onToggle} aria-label={tr('settings.toggleVisibility')}>
            {show ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
          </Button>
          <Button size="icon" variant="ghost" onClick={onCopy} aria-label="Copy" disabled={!value}>
            <Copy className="h-3.5 w-3.5" />
          </Button>
          {copied ? <Badge variant="success">{tr('settings.copied')}</Badge> : null}
        </div>
        <p className="mt-1 text-xs text-muted-foreground">{description}</p>
      </div>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[140px_1fr] items-start gap-4">
      <span className="text-xs text-muted-foreground">{label}</span>
      <div>{children}</div>
    </div>
  );
}
