'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, Save, RotateCcw, XCircle, Mail } from 'lucide-react';
import { Button, Card, CardContent, Badge, Input, Label } from '@nubase/ui';
import { apiFetch, type ApiError } from '@/lib/api';
import { useSession, isProjectReady } from '@/lib/session';
import { NotProvisioned } from '@/components/not-provisioned';
import { AuthSubNav } from '../_components/sub-nav';
import { useProjectRef } from '@/lib/route-params';
import { useI18n } from '@/lib/i18n';
import type { EmailTemplate, EmailTemplatesResponse } from '@/lib/auth-types';

const TYPE_KEYS: Record<string, string> = {
  confirmation: 'authTemplates.typeConfirmation',
  recovery: 'authTemplates.typeRecovery',
  invite: 'authTemplates.typeInvite',
  magic_link: 'authTemplates.typeMagicLink',
  email_change: 'authTemplates.typeEmailChange',
  reauthentication: 'authTemplates.typeReauth',
};

export default function AuthTemplatesPage({ params }: { params: { ref: string } }) {
  const { project } = useSession();
  const projectRef = useProjectRef(params.ref);
  const ready = isProjectReady(project);
  if (!ready) {
    return <NotProvisioned projectRef={project?.ref ?? projectRef} initStatus={project?.initStatus} />;
  }
  return <TemplatesInner projectRef={projectRef} />;
}

function TemplatesInner({ projectRef }: { projectRef: string }) {
  const { tr } = useI18n();
  const trLoose = (key: string, values?: Record<string, string | number>) => tr(key as any, values);
  const { project } = useSession();
  const apikey = project!.apikey;

  const [resp, setResp] = useState<EmailTemplatesResponse | null>(null);
  const [draft, setDraft] = useState<Record<string, EmailTemplate>>({});
  const [overridden, setOverridden] = useState<Record<string, boolean>>({});
  const [snapshot, setSnapshot] = useState('');
  const [selected, setSelected] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await apiFetch<EmailTemplatesResponse>('/auth/v1/admin/settings/email-templates', { apikey });
      setResp(r);
      const d = structuredClone(r.templates ?? {});
      const o = structuredClone(r.customized ?? {});
      setDraft(d);
      setOverridden(o);
      setSnapshot(JSON.stringify({ d, o }));
      setSelected((cur) => cur || r.types?.[0] || '');
    } catch (err) {
      setError((err as ApiError).message ?? trLoose('authTemplates.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [apikey]);

  useEffect(() => {
    load();
  }, [load]);

  const dirty = useMemo(
    () => JSON.stringify({ d: draft, o: overridden }) !== snapshot,
    [draft, overridden, snapshot],
  );

  const editField = (type: string, field: keyof EmailTemplate, value: string) => {
    setDraft((cur) => {
      const base: EmailTemplate = { subject: cur[type]?.subject ?? '', content: cur[type]?.content ?? '' };
      return { ...cur, [type]: { ...base, [field]: value } };
    });
    setOverridden((cur) => ({ ...cur, [type]: true }));
  };

  const appendVar = (type: string, name: string) => {
    editField(type, 'content', (draft[type]?.content ?? '') + `{{ .${name} }}`);
  };

  const putTemplates = async (overMap: Record<string, boolean>) => {
    const templates: Record<string, EmailTemplate> = {};
    (resp?.types ?? []).forEach((t) => {
      if (overMap[t] && draft[t]) templates[t] = draft[t];
    });
    await apiFetch('/auth/v1/admin/settings/email-templates', { method: 'PUT', body: { templates }, apikey });
  };

  const save = async () => {
    setSaving(true);
    setResult(null);
    try {
      await putTemplates(overridden);
      setResult({ ok: true, message: trLoose('authTemplates.saved') });
      await load();
    } catch (err) {
      setResult({ ok: false, message: (err as ApiError).message ?? trLoose('authCommon.saveFailed') });
    } finally {
      setSaving(false);
    }
  };

  const resetType = async (type: string) => {
    setSaving(true);
    setResult(null);
    try {
      const nextOver = { ...overridden, [type]: false };
      await putTemplates(nextOver); // omits this type → reverts to built-in default
      setResult({ ok: true, message: trLoose('authTemplates.resetToDefault', { name: trLoose(TYPE_KEYS[type] ?? type) }) });
      await load();
    } catch (err) {
      setResult({ ok: false, message: (err as ApiError).message ?? trLoose('authTemplates.resetFailed') });
    } finally {
      setSaving(false);
    }
  };

  const revert = () => {
    if (resp) {
      setDraft(structuredClone(resp.templates ?? {}));
      setOverridden(structuredClone(resp.customized ?? {}));
      setSnapshot(JSON.stringify({ d: resp.templates ?? {}, o: resp.customized ?? {} }));
    }
    setResult(null);
  };

  const current = selected ? draft[selected] : undefined;
  const vars = (selected && resp?.variables?.[selected]) || [];

  return (
    <div className="flex h-full flex-col">
      <AuthSubNav projectRef={projectRef} active="templates" />

      <div className="flex-1 overflow-auto">
        <div className="max-w-5xl space-y-4 p-6">
          <header className="flex items-start justify-between gap-4">
            <div>
              <h1 className="text-lg font-semibold">{tr('authTemplates.title')}</h1>
              <p className="text-xs text-muted-foreground">
                {tr('authTemplates.descPrefix')}{' '}
                <code className="mx-1 rounded bg-muted/40 px-1">{projectRef}</code>{' '}
                {tr('authTemplates.descMid')}{' '}
                <code className="mx-1 rounded bg-muted/40 px-1">{'{{ .Variable }}'}</code>{' '}
                {tr('authTemplates.descSuffix')}
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              {dirty && (
                <Button size="sm" variant="outline" onClick={revert} disabled={saving}>
                  <RotateCcw className="h-3.5 w-3.5" /> {tr('authCommon.discard')}
                </Button>
              )}
              <Button size="sm" variant="brand" onClick={save} disabled={!dirty || saving}>
                <Save className={'h-3.5 w-3.5 ' + (saving ? 'animate-pulse' : '')} />
                {saving ? trLoose('authCommon.saving') : trLoose('authCommon.save')}
              </Button>
              <Button size="sm" variant="outline" onClick={load} disabled={loading || saving}>
                <RefreshCw className={'h-3.5 w-3.5 ' + (loading ? 'animate-spin' : '')} /> {tr('authCommon.refresh')}
              </Button>
            </div>
          </header>

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

          {loading && !resp ? (
            <p className="py-8 text-center text-sm text-muted-foreground">{tr('authCommon.loading')}</p>
          ) : resp ? (
            <div className="grid grid-cols-[200px_1fr] gap-4">
              {/* type list */}
              <Card className="self-start">
                <CardContent className="p-2">
                  <ul className="space-y-0.5">
                    {resp.types.map((t) => (
                      <li key={t}>
                        <button
                          onClick={() => setSelected(t)}
                          className={
                            'flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-xs transition-colors ' +
                            (selected === t ? 'bg-accent/70 font-medium text-foreground' : 'text-muted-foreground hover:bg-accent/30 hover:text-foreground')
                          }
                        >
                          <span className="flex items-center gap-1.5">
                            <Mail className="h-3.5 w-3.5 text-muted-foreground" />
                            {trLoose(TYPE_KEYS[t] ?? t)}
                          </span>
                          {overridden[t] && <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" title={tr('authTemplates.customized')} />}
                        </button>
                      </li>
                    ))}
                  </ul>
                </CardContent>
              </Card>

              {/* editor */}
              <Card>
                <CardContent className="space-y-3 p-4">
                  {current ? (
                    <>
                      <div className="flex items-center justify-between">
                        <h3 className="text-sm font-semibold">{trLoose(TYPE_KEYS[selected] ?? selected)}</h3>
                        <div className="flex items-center gap-2">
                          {overridden[selected] ? (
                            <Badge variant="default">{tr('authTemplates.customized')}</Badge>
                          ) : (
                            <Badge variant="outline" className="text-muted-foreground">{tr('authTemplates.default')}</Badge>
                          )}
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => resetType(selected)}
                            disabled={saving || !overridden[selected]}
                            title={tr('authTemplates.revertTitle')}
                          >
                            <RotateCcw className="h-3.5 w-3.5" /> {tr('authTemplates.reset')}
                          </Button>
                        </div>
                      </div>

                      <div className="space-y-1">
                        <Label className="text-xs">{tr('authTemplates.subject')}</Label>
                        <Input
                          value={current.subject ?? ''}
                          onChange={(e) => editField(selected, 'subject', e.target.value)}
                          className="h-8 text-xs"
                        />
                      </div>

                      <div className="space-y-1">
                        <div className="flex items-center justify-between">
                          <Label className="text-xs">{tr('authTemplates.body')}</Label>
                          <div className="flex flex-wrap gap-1">
                            {vars.map((v) => (
                              <button
                                key={v}
                                onClick={() => appendVar(selected, v)}
                                className="rounded border border-border bg-muted/30 px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground transition-colors hover:border-muted-foreground/40 hover:bg-muted/60 hover:text-foreground"
                                title={trLoose('authTemplates.insertVar', { var: v })}
                              >
                                {'{{ .' + v + ' }}'}
                              </button>
                            ))}
                          </div>
                        </div>
                        <textarea
                          value={current.content ?? ''}
                          onChange={(e) => editField(selected, 'content', e.target.value)}
                          rows={16}
                          spellCheck={false}
                          className="w-full rounded-md border border-input bg-transparent px-2.5 py-1.5 font-mono text-xs shadow-sm transition-colors hover:border-muted-foreground/40 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                        />
                      </div>
                    </>
                  ) : (
                    <p className="text-sm text-muted-foreground">{tr('authTemplates.selectTemplate')}</p>
                  )}
                </CardContent>
              </Card>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
