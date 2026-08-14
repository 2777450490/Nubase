'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, Plus, Trash2, XCircle, Building2, Info } from 'lucide-react';
import {
  Button,
  Card,
  CardContent,
  Badge,
  Input,
  Label,
  Dialog,
  DialogHeader,
  DialogBody,
  DialogFooter,
} from '@nubase/ui';
import { apiFetch, type ApiError } from '@/lib/api';
import { useSession, isProjectReady } from '@/lib/session';
import { NotProvisioned } from '@/components/not-provisioned';
import { AuthSubNav } from '../_components/sub-nav';
import { useProjectRef } from '@/lib/route-params';
import { useI18n } from '@/lib/i18n';
import type { SsoProviderResponse, CreateSsoProviderRequest } from '@/lib/auth-types';

export default function AuthSsoPage({ params }: { params: { ref: string } }) {
  const { project } = useSession();
  const projectRef = useProjectRef(params.ref);
  const ready = isProjectReady(project);
  if (!ready) {
    return <NotProvisioned projectRef={project?.ref ?? projectRef} initStatus={project?.initStatus} />;
  }
  return <SsoInner projectRef={projectRef} />;
}

const EMPTY_FORM = {
  entityId: '',
  ssoUrl: '',
  x509Certificate: '',
  domains: '',
  resourceId: '',
};

function SsoInner({ projectRef }: { projectRef: string }) {
  const { tr } = useI18n();
  const trLoose = (key: string, values?: Record<string, string | number>) => tr(key as any, values);
  const { project } = useSession();
  const apikey = project!.apikey;

  const [providers, setProviders] = useState<SsoProviderResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);

  const [addOpen, setAddOpen] = useState(false);
  const [form, setForm] = useState({ ...EMPTY_FORM });
  const [submitting, setSubmitting] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<SsoProviderResponse | null>(null);
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await apiFetch<SsoProviderResponse[]>('/auth/v1/admin/sso/providers', { apikey });
      setProviders(list ?? []);
    } catch (err) {
      setError((err as ApiError).message ?? trLoose('authSso.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [apikey]);

  useEffect(() => {
    load();
  }, [load]);

  const canSubmit = form.entityId.trim() && form.ssoUrl.trim() && form.x509Certificate.trim();

  const create = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setResult(null);
    try {
      const body: CreateSsoProviderRequest = {
        type: 'saml',
        entityId: form.entityId.trim(),
        ssoUrl: form.ssoUrl.trim(),
        x509Certificate: form.x509Certificate.trim(),
        domains: form.domains
          .split('\n')
          .map((s) => s.trim())
          .filter(Boolean),
        resourceId: form.resourceId.trim() || undefined,
      };
      await apiFetch('/auth/v1/admin/sso/providers', { method: 'POST', body, apikey });
      setResult({ ok: true, message: trLoose('authSso.created') });
      setAddOpen(false);
      setForm({ ...EMPTY_FORM });
      await load();
    } catch (err) {
      setResult({ ok: false, message: (err as ApiError).message ?? trLoose('authSso.createFailed') });
    } finally {
      setSubmitting(false);
    }
  };

  const performDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    setResult(null);
    try {
      await apiFetch(`/auth/v1/admin/sso/providers/${deleteTarget.id}`, { method: 'DELETE', apikey });
      setResult({ ok: true, message: trLoose('authSso.deleted') });
      setDeleteTarget(null);
      await load();
    } catch (err) {
      setResult({ ok: false, message: (err as ApiError).message ?? trLoose('authSso.deleteFailed') });
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="flex h-full flex-col">
      <AuthSubNav projectRef={projectRef} active="sso" />

      <div className="flex-1 overflow-auto">
        <div className="max-w-4xl space-y-6 p-6">
          <header className="flex items-start justify-between gap-4">
            <div>
              <h1 className="text-lg font-semibold">{tr('authSso.title')}</h1>
              <p className="text-xs text-muted-foreground">
                {tr('authSso.descPrefix')}{' '}
                <code className="mx-1 rounded bg-muted/40 px-1">{projectRef}</code>{' '}
                {tr('authSso.descSuffix')}
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <Button size="sm" variant="brand" onClick={() => { setResult(null); setForm({ ...EMPTY_FORM }); setAddOpen(true); }}>
                <Plus className="h-3.5 w-3.5" /> {tr('authSso.addProvider')}
              </Button>
              <Button size="sm" variant="outline" onClick={load} disabled={loading}>
                <RefreshCw className={'h-3.5 w-3.5 ' + (loading ? 'animate-spin' : '')} /> {tr('authCommon.refresh')}
              </Button>
            </div>
          </header>

          {/* SP metadata hint */}
          <Card className="border-border/60">
            <CardContent className="flex items-start gap-2 p-3 text-xs text-muted-foreground">
              <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <div>
                {tr('authSso.spHint')}
                <div className="mt-1 font-mono">
                  {tr('authSso.spHintMetadata')} <code className="rounded bg-muted/40 px-1">/auth/v1/sso/saml/metadata</code>
                  &nbsp;·&nbsp; {tr('authSso.spHintAcs')}: <code className="rounded bg-muted/40 px-1">/auth/v1/sso/saml/acs</code>
                </div>
              </div>
            </CardContent>
          </Card>

          {result && (
            <p className={'text-xs ' + (result.ok ? 'text-emerald-500' : 'text-destructive')}>{result.message}</p>
          )}
          {error && (
            <Card>
              <CardContent className="flex items-center gap-2 p-4 text-sm text-destructive">
                <XCircle className="h-4 w-4" /> {error}
              </CardContent>
            </Card>
          )}

          {loading && providers.length === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">{tr('authCommon.loading')}</p>
          ) : providers.length === 0 ? (
            <Card>
              <CardContent className="flex flex-col items-center gap-2 p-10 text-center">
                <Building2 className="h-6 w-6 text-muted-foreground" />
                <p className="text-sm font-medium">{tr('authSso.emptyTitle')}</p>
                <p className="text-xs text-muted-foreground">{tr('authSso.emptyDesc')}</p>
              </CardContent>
            </Card>
          ) : (
            <Card>
              <CardContent className="p-0">
                <table className="w-full text-xs">
                  <thead className="border-b border-border text-muted-foreground">
                    <tr>
                      <th className="px-4 py-2 text-left font-medium">{tr('authSso.colEntityId')}</th>
                      <th className="px-4 py-2 text-left font-medium">{tr('authSso.colDomains')}</th>
                      <th className="px-4 py-2 text-left font-medium">{tr('authSso.colSsoUrl')}</th>
                      <th className="px-4 py-2 text-left font-medium">{tr('authSso.colStatus')}</th>
                      <th className="px-4 py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {providers.map((p) => (
                      <tr key={p.id} className="border-b border-border/40 transition-colors last:border-b-0 hover:bg-muted/30">
                        <td className="px-4 py-2 font-mono">{p.entityId ?? '—'}</td>
                        <td className="px-4 py-2">
                          {p.domains?.length ? (
                            <div className="flex flex-wrap gap-1">
                              {p.domains.map((d) => (
                                <Badge key={d} variant="outline">{d}</Badge>
                              ))}
                            </div>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="max-w-[200px] truncate px-4 py-2 font-mono" title={p.ssoUrl ?? ''}>
                          {p.ssoUrl ?? '—'}
                        </td>
                        <td className="px-4 py-2">
                          {p.enabled ? (
                            <Badge variant="default">{tr('authSso.enabled')}</Badge>
                          ) : (
                            <Badge variant="outline" className="text-muted-foreground">{tr('authSso.disabled')}</Badge>
                          )}
                        </td>
                        <td className="px-4 py-2 text-right">
                          <Button size="sm" variant="outline" onClick={() => { setResult(null); setDeleteTarget(p); }}>
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </CardContent>
            </Card>
          )}
        </div>
      </div>

      {/* Add provider dialog */}
      <Dialog open={addOpen} onOpenChange={setAddOpen}>
        <DialogHeader>{tr('authSso.addTitle')}</DialogHeader>
        <DialogBody>
          <div className="space-y-3">
            <FormField label={tr('authSso.fEntityId')} hint={tr('authSso.fEntityIdHint')}>
              <Input value={form.entityId} onChange={(e) => setForm({ ...form, entityId: e.target.value })} placeholder="https://idp.example.com/saml/metadata" className="h-8 text-xs" />
            </FormField>
            <FormField label={tr('authSso.fSsoUrl')} hint={tr('authSso.fSsoUrlHint')}>
              <Input value={form.ssoUrl} onChange={(e) => setForm({ ...form, ssoUrl: e.target.value })} placeholder="https://idp.example.com/sso" className="h-8 text-xs" />
            </FormField>
            <FormField label={tr('authSso.fDomains')} hint={tr('authSso.fDomainsHint')}>
              <textarea
                value={form.domains}
                onChange={(e) => setForm({ ...form, domains: e.target.value })}
                rows={2}
                placeholder={'acme.com\ncorp.acme.com'}
                className="w-full rounded-md border border-input bg-transparent px-2.5 py-1.5 font-mono text-xs shadow-sm transition-colors hover:border-muted-foreground/40 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              />
            </FormField>
            <FormField label={tr('authSso.fCert')} hint={tr('authSso.fCertHint')}>
              <textarea
                value={form.x509Certificate}
                onChange={(e) => setForm({ ...form, x509Certificate: e.target.value })}
                rows={4}
                placeholder={'-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----'}
                className="w-full rounded-md border border-input bg-transparent px-2.5 py-1.5 font-mono text-xs shadow-sm transition-colors hover:border-muted-foreground/40 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              />
            </FormField>
            <FormField label={tr('authSso.fResource')} hint={tr('authSso.fResourceHint')}>
              <Input value={form.resourceId} onChange={(e) => setForm({ ...form, resourceId: e.target.value })} placeholder="okta-prod" className="h-8 text-xs" />
            </FormField>
          </div>
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={() => setAddOpen(false)} disabled={submitting}>{tr('authCommon.cancel')}</Button>
          <Button variant="brand" onClick={create} disabled={!canSubmit || submitting}>
            {submitting ? trLoose('authSso.creating') : trLoose('authSso.create')}
          </Button>
        </DialogFooter>
      </Dialog>

      {/* Delete confirmation */}
      <Dialog open={Boolean(deleteTarget)} onOpenChange={(o) => !o && setDeleteTarget(null)}>
        <DialogHeader>{tr('authSso.deleteTitle')}</DialogHeader>
        <DialogBody>
          <p className="text-sm">
            {tr('authSso.deleteBodyPrefix')} <code className="rounded bg-muted/40 px-1">{deleteTarget?.entityId}</code>
            {tr('authSso.deleteBodySuffix')}
          </p>
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={() => setDeleteTarget(null)} disabled={deleting}>{tr('authCommon.cancel')}</Button>
          <Button variant="destructive" onClick={performDelete} disabled={deleting}>
            {deleting ? trLoose('authSso.deleting') : trLoose('authSso.delete')}
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}

function FormField({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <Label className="text-xs">{label}</Label>
      {children}
      {hint && <p className="text-[10px] text-muted-foreground">{hint}</p>}
    </div>
  );
}
