'use client';

import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import {
  Brain,
  Cpu,
  Database,
  Search,
  History as HistoryIcon,
  Network,
  Layers,
  CheckCircle2,
  XCircle,
  RefreshCw,
  AlertTriangle,
  Trash2,
  Wrench,
  Save,
} from 'lucide-react';
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
import { useI18n } from '@/lib/i18n';
import { NotProvisioned } from '@/components/not-provisioned';
import { MemorySubNav } from '../_components/sub-nav';
import { useProjectRef } from '@/lib/route-params';

// Mirrors ai.nubase.mem.dto.MemConfigResponse
interface MemConfig {
  enabled: boolean;
  chat: { provider: string; model: string; temperature: number };
  embedding: {
    provider: string;
    model: string;
    dimensions: number;
    cacheEnabled: boolean;
    cacheMaximumSize: number;
    cacheTtlMinutes: number;
  };
  search: {
    defaultTopK: number;
    defaultThreshold: number;
    entityBoostEnabled: boolean;
    entityMatchSimilarity: number;
    ftsConfig: string;
  };
  session: { enabled: boolean; maxMessages: number; injectIntoExtraction: boolean };
  entity: { maxLinkedMemoryIds: number };
  historyEnabled: boolean;
  providerStatus: {
    chatAvailable: boolean;
    chatProviderName: string;
    embeddingAvailable: boolean;
    embeddingProviderName: string;
  };
  providers?: {
    openai?: ProviderCreds;
    anthropic?: ProviderCreds;
    generic?: ProviderCreds;
  };
}

interface ProviderCreds {
  authTokenSet: boolean;
  baseUrl?: string;
}

export default function MemorySettingsPage({ params }: { params: { ref: string } }) {
  const { project } = useSession();
  const projectRef = useProjectRef(params.ref);
  const ready = isProjectReady(project);
  if (!ready) {
    return <NotProvisioned projectRef={project?.ref ?? projectRef} initStatus={project?.initStatus} />;
  }
  return <SettingsInner projectRef={projectRef} />;
}

function SettingsInner({ projectRef }: { projectRef: string }) {
  const { tr } = useI18n();
  const trLoose = (key: string, values?: Record<string, string | number>) => tr(key as any, values);
  const { project, platformKey } = useSession();
  const apikey = project!.apikey;
  const router = useRouter();

  const [config, setConfig] = useState<MemConfig | null>(null);
  const [draft, setDraft] = useState<EditableDraft>(EMPTY_DRAFT);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveResult, setSaveResult] = useState<{ ok: boolean; message: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [confirmMigrate, setConfirmMigrate] = useState(false);
  const [migrating, setMigrating] = useState(false);
  const [migrateResult, setMigrateResult] = useState<{ ok: boolean; message: string } | null>(null);

  const [confirmReset, setConfirmReset] = useState(false);
  const [resetTypedName, setResetTypedName] = useState('');
  const [resetting, setResetting] = useState(false);
  const [resetResult, setResetResult] = useState<{ ok: boolean; message: string } | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const c = await apiFetch<MemConfig>('/mem/v1/config', { apikey });
      setConfig(c);
      setDraft(draftFromConfig(c));
    } catch (err) {
      setError((err as ApiError).message ?? trLoose('memSettings.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [apikey]);

  useEffect(() => { load(); }, [load]);

  const dirty = config ? draftDiffersFrom(draft, config) : false;

  const save = async () => {
    if (!config) return;
    setSaving(true);
    setSaveResult(null);
    try {
      const updated = await apiFetch<MemConfig>('/mem/v1/config', {
        method: 'PUT',
        body: draftToPatch(draft),
        apikey,
      });
      setConfig(updated);
      setDraft(draftFromConfig(updated));
      setSaveResult({ ok: true, message: trLoose('memSettings.saved') });
    } catch (err) {
      setSaveResult({
        ok: false,
        message: (err as ApiError).message ?? trLoose('memSettings.saveFailed'),
      });
    } finally {
      setSaving(false);
    }
  };

  const revert = () => {
    if (config) setDraft(draftFromConfig(config));
    setSaveResult(null);
  };

  // -------- migrate mem-schema (admin endpoint at /auth/v1) --------
  const performMigrate = async () => {
    setConfirmMigrate(false);
    setMigrating(true);
    setMigrateResult(null);
    try {
      // The migrate endpoint is under /auth/v1/admin and expects the platform-user JWT
      // as the apikey (same as other admin endpoints in this studio). Falls back to the
      // project's service-role token if platformKey is missing.
      const res = await apiFetch<{ success: boolean; steps?: string[]; error?: string }>(
        `/auth/v1/admin/init/mem-schema?dbKey=${encodeURIComponent(projectRef)}`,
        { apikey: platformKey ?? apikey, method: 'POST' },
      );
      if (res.success) {
        setMigrateResult({
          ok: true,
          message: trLoose('memSettings.migrated', { n: res.steps?.length ?? 0 }),
        });
        // Refresh config in case dimensions/fts-config affect anything visible.
        await load();
      } else {
        setMigrateResult({ ok: false, message: res.error ?? trLoose('memSettings.migrationFailed') });
      }
    } catch (err) {
      setMigrateResult({
        ok: false,
        message: (err as ApiError).message ?? trLoose('memSettings.migrationFailed'),
      });
    } finally {
      setMigrating(false);
    }
  };

  // -------- reset (service-role only) --------
  const resetExpected = project?.name?.trim() || projectRef;
  const resetUnlocked = resetTypedName.trim() === resetExpected;

  const performReset = async () => {
    if (!resetUnlocked) return;
    setConfirmReset(false);
    setResetting(true);
    setResetResult(null);
    try {
      await apiFetch('/mem/v1/reset', { apikey, method: 'POST' });
      setResetResult({
        ok: true,
        message: trLoose('memSettings.resetDone'),
      });
      setResetTypedName('');
    } catch (err) {
      setResetResult({
        ok: false,
        message: (err as ApiError).message ?? trLoose('memSettings.resetFailed'),
      });
    } finally {
      setResetting(false);
    }
  };

  return (
    <div className="flex h-full flex-col">
      <MemorySubNav projectRef={projectRef} active="settings" />

      <div className="flex-1 overflow-auto">
        <div className="mx-auto max-w-4xl space-y-6 p-6">
          <header className="flex items-center justify-between">
            <div>
              <h1 className="text-lg font-semibold">{tr('memSettings.title')}</h1>
              <p className="text-xs text-muted-foreground">
                {tr('memSettings.descPrefix', { ref: projectRef })}{' '}
                <code className="mx-1 rounded bg-muted/40 px-1">application.yml</code>{' '}
                {tr('memSettings.descSuffix')}
              </p>
            </div>
            <div className="flex items-center gap-2">
              {dirty && (
                <Button size="sm" variant="outline" onClick={revert} disabled={saving}>
                  {tr('memSettings.discard')}
                </Button>
              )}
              <Button size="sm" variant="brand" onClick={save} disabled={!dirty || saving}>
                <Save className={'h-3.5 w-3.5 ' + (saving ? 'animate-pulse' : '')} />
                {saving ? trLoose('memSettings.saving') : trLoose('memSettings.save')}
              </Button>
              <Button size="sm" variant="outline" onClick={load} disabled={loading || saving}>
                <RefreshCw className={'h-3.5 w-3.5 ' + (loading ? 'animate-spin' : '')} /> {tr('memSettings.refresh')}
              </Button>
            </div>
          </header>

          {saveResult && (
            <p className={'text-xs ' + (saveResult.ok ? 'text-emerald-500' : 'text-destructive')}>
              {saveResult.message}
            </p>
          )}

          {error && (
            <Card>
              <CardContent className="flex items-center gap-2 p-4 text-sm text-destructive">
                <XCircle className="h-4 w-4" /> {error}
              </CardContent>
            </Card>
          )}

          {loading && !config && (
            <p className="py-8 text-center text-sm text-muted-foreground">{tr('memSettings.loading')}</p>
          )}

          {config && (
            <>
              {/* Feature flag */}
              <SectionCard
                icon={Brain}
                title={tr('memSettings.sFeature')}
                description={tr('memSettings.sFeatureDesc')}
              >
                <Row label={tr('memSettings.enabled')}>
                  <BoolBadge value={config.enabled} />
                  <Locked reason={trLoose('memSettings.lockFeature')} />
                </Row>
                <Row label={tr('memSettings.historyAudit')}>
                  <BoolInput
                    value={draft.historyEnabled}
                    onChange={(v) => setDraft({ ...draft, historyEnabled: v })}
                  />
                </Row>
              </SectionCard>

              {/* Chat LLM */}
              <SectionCard
                icon={Cpu}
                title={tr('memSettings.sChat')}
                description={tr('memSettings.sChatDesc')}
              >
                <Row label={tr('memSettings.provider')}>
                  <SelectInput
                    value={draft.chatProvider}
                    onChange={(v) => setDraft({ ...draft, chatProvider: v })}
                    options={['openai', 'anthropic', 'generic']}
                  />
                  <ProviderStatusBadge available={config.providerStatus.chatAvailable} />
                </Row>
                <Row label={tr('memSettings.model')}>
                  <TextInput
                    value={draft.chatModel}
                    onChange={(v) => setDraft({ ...draft, chatModel: v })}
                    placeholder="gpt-4o-mini"
                  />
                </Row>
                <Row label={tr('memSettings.temperature')}>
                  <NumberInput
                    value={draft.chatTemperature}
                    min={0}
                    max={2}
                    step={0.1}
                    onChange={(v) => setDraft({ ...draft, chatTemperature: v })}
                  />
                </Row>
              </SectionCard>

              {/* Embedding */}
              <SectionCard
                icon={Layers}
                title={tr('memSettings.sEmbedding')}
                description={tr('memSettings.sEmbeddingDesc')}
              >
                <Row label={tr('memSettings.provider')}>
                  <SelectInput
                    value={draft.embeddingProvider}
                    onChange={(v) => setDraft({ ...draft, embeddingProvider: v })}
                    options={['openai', 'generic']}
                  />
                  <ProviderStatusBadge available={config.providerStatus.embeddingAvailable} />
                </Row>
                <Row label={tr('memSettings.model')}>
                  <TextInput
                    value={draft.embeddingModel}
                    onChange={(v) => setDraft({ ...draft, embeddingModel: v })}
                    placeholder="text-embedding-3-small"
                  />
                  <span className="ml-2 text-[10px] text-muted-foreground">
                    {trLoose('memSettings.mustOutputDims', { n: config.embedding.dimensions })}
                  </span>
                </Row>
                <Row label={tr('memSettings.dimensions')}>
                  <span className="font-medium">{config.embedding.dimensions}</span>
                  <Locked reason={trLoose('memSettings.lockDimensions')} />
                </Row>
                <Row label={tr('memSettings.inProcCache')}>
                  <BoolBadge value={config.embedding.cacheEnabled} />
                  {config.embedding.cacheEnabled && (
                    <span className="ml-2 text-[10px] text-muted-foreground">
                      {trLoose('memSettings.cacheHint', { max: config.embedding.cacheMaximumSize.toLocaleString(), ttl: config.embedding.cacheTtlMinutes })}
                    </span>
                  )}
                  <Locked reason={trLoose('memSettings.lockCache')} />
                </Row>
              </SectionCard>

              {/* Provider credentials */}
              <SectionCard
                icon={Cpu}
                title={tr('memSettings.sCreds')}
                description={tr('memSettings.sCredsDesc')}
              >
                <ProviderCredsBlock
                  label="OpenAI"
                  baseUrl={draft.openaiBaseUrl}
                  setBaseUrl={(v) => setDraft({ ...draft, openaiBaseUrl: v })}
                  authToken={draft.openaiAuthToken}
                  setAuthToken={(v) => setDraft({ ...draft, openaiAuthToken: v })}
                  tokenSet={Boolean(config.providers?.openai?.authTokenSet)}
                  baseUrlPlaceholder="https://api.openai.com"
                />
                <ProviderCredsBlock
                  label="Anthropic"
                  baseUrl={draft.anthropicBaseUrl}
                  setBaseUrl={(v) => setDraft({ ...draft, anthropicBaseUrl: v })}
                  authToken={draft.anthropicAuthToken}
                  setAuthToken={(v) => setDraft({ ...draft, anthropicAuthToken: v })}
                  tokenSet={Boolean(config.providers?.anthropic?.authTokenSet)}
                  baseUrlPlaceholder="https://api.anthropic.com"
                />
                <ProviderCredsBlock
                  label={tr('memSettings.providerGeneric')}
                  baseUrl={draft.genericBaseUrl}
                  setBaseUrl={(v) => setDraft({ ...draft, genericBaseUrl: v })}
                  authToken={draft.genericAuthToken}
                  setAuthToken={(v) => setDraft({ ...draft, genericAuthToken: v })}
                  tokenSet={Boolean(config.providers?.generic?.authTokenSet)}
                  baseUrlPlaceholder="https://dashscope.aliyuncs.com/compatible-mode/v1"
                />
              </SectionCard>

              {/* Search */}
              <SectionCard
                icon={Search}
                title={tr('memSettings.sSearch')}
                description={tr('memSettings.sSearchDesc')}
              >
                <Row label={tr('memSettings.defaultTopK')}>
                  <NumberInput
                    value={draft.searchDefaultTopK}
                    min={1}
                    step={1}
                    onChange={(v) => setDraft({ ...draft, searchDefaultTopK: v })}
                  />
                </Row>
                <Row label={tr('memSettings.defaultThreshold')}>
                  <NumberInput
                    value={draft.searchDefaultThreshold}
                    min={0}
                    step={0.05}
                    onChange={(v) => setDraft({ ...draft, searchDefaultThreshold: v })}
                  />
                  <span className="ml-2 text-[10px] text-muted-foreground">{tr('memSettings.cosineCap')}</span>
                </Row>
                <Row label={tr('memSettings.entityBoost')}>
                  <BoolInput
                    value={draft.searchEntityBoostEnabled}
                    onChange={(v) => setDraft({ ...draft, searchEntityBoostEnabled: v })}
                  />
                </Row>
                <Row label={tr('memSettings.entityMatchSimilarity')}>
                  <NumberInput
                    value={draft.searchEntityMatchSimilarity}
                    min={0}
                    max={1}
                    step={0.05}
                    onChange={(v) =>
                      setDraft({ ...draft, searchEntityMatchSimilarity: v })
                    }
                    disabled={!draft.searchEntityBoostEnabled}
                  />
                </Row>
                <Row label={tr('memSettings.bm25FtsConfig')}>
                  <code className="text-xs">{config.search.ftsConfig}</code>
                  <span className="ml-2 text-[10px] text-muted-foreground">
                    {config.search.ftsConfig === 'simple' && trLoose('memSettings.ftsSimple')}
                    {config.search.ftsConfig === 'english' && trLoose('memSettings.ftsEnglish')}
                    {config.search.ftsConfig === 'zhparser' && trLoose('memSettings.ftsZhparser')}
                  </span>
                  <Locked reason={trLoose('memSettings.lockFts')} />
                </Row>
              </SectionCard>

              {/* Session */}
              <SectionCard
                icon={HistoryIcon}
                title={tr('memSettings.sSession')}
                description={tr('memSettings.sSessionDesc')}
              >
                <Row label={tr('memSettings.enabled')}>
                  <BoolInput
                    value={draft.sessionEnabled}
                    onChange={(v) => setDraft({ ...draft, sessionEnabled: v })}
                  />
                </Row>
                <Row label={tr('memSettings.maxMessages')}>
                  <NumberInput
                    value={draft.sessionMaxMessages}
                    min={1}
                    step={1}
                    onChange={(v) => setDraft({ ...draft, sessionMaxMessages: v })}
                    disabled={!draft.sessionEnabled}
                  />
                </Row>
                <Row label={tr('memSettings.injectIntoExtraction')}>
                  <BoolInput
                    value={draft.sessionInjectIntoExtraction}
                    onChange={(v) =>
                      setDraft({ ...draft, sessionInjectIntoExtraction: v })
                    }
                    disabled={!draft.sessionEnabled}
                  />
                </Row>
              </SectionCard>

              {/* Entity cap */}
              <SectionCard
                icon={Network}
                title={tr('memSettings.sEntity')}
                description={tr('memSettings.sEntityDesc')}
              >
                <Row label={tr('memSettings.maxLinksPerEntity')}>
                  <NumberInput
                    value={draft.entityMaxLinkedMemoryIds}
                    min={1}
                    step={100}
                    onChange={(v) =>
                      setDraft({ ...draft, entityMaxLinkedMemoryIds: v })
                    }
                  />
                </Row>
              </SectionCard>

              {/* Danger Zone */}
              <Card className="border-destructive/50">
                <CardContent className="p-4">
                  <div className="mb-3 flex items-center gap-2">
                    <AlertTriangle className="h-4 w-4 text-destructive" />
                    <h2 className="text-sm font-semibold text-destructive">{tr('memSettings.dangerZone')}</h2>
                  </div>

                  {/* Migrate */}
                  <div className="flex items-start justify-between gap-4 border-t border-border pt-3">
                    <div className="min-w-0">
                      <div className="text-sm font-medium">{tr('memSettings.migrateTitle')}</div>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {tr('memSettings.migrateDescPrefix')}{' '}
                        <code className="rounded bg-muted/40 px-1">init_mem_schema.sql</code>{' '}
                        {tr('memSettings.migrateDescSuffix')}
                      </p>
                      {migrateResult && (
                        <div
                          className={
                            'mt-2 flex items-start gap-2 rounded-md p-2 text-xs ' +
                            (migrateResult.ok
                              ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                              : 'bg-destructive/10 text-destructive')
                          }
                        >
                          {migrateResult.ok
                            ? <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                            : <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />}
                          <span>{migrateResult.message}</span>
                        </div>
                      )}
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => { setMigrateResult(null); setConfirmMigrate(true); }}
                      disabled={migrating}
                    >
                      <Wrench className="h-3.5 w-3.5" /> {migrating ? trLoose('memSettings.migrating') : trLoose('memSettings.migrate')}
                    </Button>
                  </div>

                  {/* Reset */}
                  <div className="mt-4 flex items-start justify-between gap-4 border-t border-border pt-3">
                    <div className="min-w-0">
                      <div className="text-sm font-medium text-destructive">{tr('memSettings.resetTitle')}</div>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {tr('memSettings.resetDescPrefix')}{' '}
                        <code className="rounded bg-muted/40 px-1">mem.memories</code>,{' '}
                        <code className="rounded bg-muted/40 px-1">mem.memory_history</code>,{' '}
                        <code className="rounded bg-muted/40 px-1">mem.session_messages</code>{' '}
                        {tr('memSettings.resetDescAnd')}{' '}
                        <code className="rounded bg-muted/40 px-1">mem.entities</code>
                        {tr('memSettings.resetDescSuffix')}
                      </p>
                      {resetResult && (
                        <div
                          className={
                            'mt-2 flex items-start gap-2 rounded-md p-2 text-xs ' +
                            (resetResult.ok
                              ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                              : 'bg-destructive/10 text-destructive')
                          }
                        >
                          {resetResult.ok
                            ? <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                            : <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />}
                          <span>{resetResult.message}</span>
                        </div>
                      )}
                    </div>
                    <Button
                      size="sm"
                      variant="destructive"
                      onClick={() => { setResetResult(null); setResetTypedName(''); setConfirmReset(true); }}
                      disabled={resetting}
                    >
                      <Trash2 className="h-3.5 w-3.5" /> {resetting ? trLoose('memSettings.resetting') : trLoose('memSettings.reset')}
                    </Button>
                  </div>
                </CardContent>
              </Card>
            </>
          )}
        </div>
      </div>

      {/* Migrate confirmation */}
      <Dialog open={confirmMigrate} onOpenChange={setConfirmMigrate}>
        <DialogHeader>
          {tr('memSettings.confirmMigratePrefix')} <code>{projectRef}</code>{tr('memSettings.confirmMigrateSuffix')}
        </DialogHeader>
        <DialogBody>
          <p className="text-sm">
            {tr('memSettings.confirmMigrateBodyPrefix')}{' '}
            <code>IF NOT EXISTS</code> {tr('memSettings.confirmMigrateBodyMid')}{' '}
            <code>DROP POLICY IF EXISTS</code> {tr('memSettings.confirmMigrateBodySuffix')}
          </p>
          <p className="mt-2 text-xs text-muted-foreground">
            {tr('memSettings.confirmMigrateNote')}
          </p>
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={() => setConfirmMigrate(false)}>{tr('memSettings.cancel')}</Button>
          <Button onClick={performMigrate}>{tr('memSettings.runMigration')}</Button>
        </DialogFooter>
      </Dialog>

      {/* Reset confirmation */}
      <Dialog open={confirmReset} onOpenChange={setConfirmReset}>
        <DialogHeader>{tr('memSettings.confirmResetTitle')}</DialogHeader>
        <DialogBody>
          <div className="space-y-3">
            <div className="flex items-start gap-2 rounded-md bg-destructive/10 p-2 text-xs text-destructive">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>
                {tr('memSettings.confirmResetBodyPrefix')} <strong>{projectRef}</strong>
                {tr('memSettings.confirmResetBodySuffix')}
              </span>
            </div>
            <div className="space-y-1">
              <Label className="text-xs">
                {tr('memSettings.typeToConfirmPrefix')}{' '}
                <code className="rounded bg-muted/40 px-1">{resetExpected}</code>{' '}
                {tr('memSettings.typeToConfirmSuffix')}
              </Label>
              <Input
                value={resetTypedName}
                onChange={(e) => setResetTypedName(e.target.value)}
                placeholder={resetExpected}
                className="h-8 text-xs"
              />
            </div>
          </div>
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={() => setConfirmReset(false)}>{tr('memSettings.cancel')}</Button>
          <Button variant="destructive" disabled={!resetUnlocked} onClick={performReset}>
            {tr('memSettings.confirmResetBtn')}
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}

// ---------- presentational helpers ----------

function SectionCard({
  icon: Icon,
  title,
  description,
  children,
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardContent className="p-4">
        <div className="mb-2 flex items-start gap-2">
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-accent/40">
            <Icon className="h-3.5 w-3.5 text-muted-foreground" />
          </div>
          <div className="min-w-0">
            <h3 className="text-sm font-semibold">{title}</h3>
            {description && <p className="text-[11px] text-muted-foreground">{description}</p>}
          </div>
        </div>
        <dl className="mt-3 space-y-1.5 text-xs">{children}</dl>
      </CardContent>
    </Card>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[160px_1fr] items-center gap-2 border-b border-border/40 py-1 last:border-b-0">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="flex items-center text-foreground">{children}</dd>
    </div>
  );
}

function BoolBadge({ value }: { value: boolean }) {
  const { tr } = useI18n();
  return value ? (
    <Badge variant="default" className="gap-1">
      <CheckCircle2 className="h-3 w-3" /> {tr('memSettings.on')}
    </Badge>
  ) : (
    <Badge variant="outline" className="gap-1 text-muted-foreground">
      <XCircle className="h-3 w-3" /> {tr('memSettings.off')}
    </Badge>
  );
}

function ProviderBadge({ name, available }: { name: string; available: boolean }) {
  return (
    <span className="flex items-center gap-1.5">
      <Badge variant="secondary">{name}</Badge>
      {available ? (
        <Badge variant="default" className="gap-1 text-[10px]">
          <CheckCircle2 className="h-3 w-3" /> available
        </Badge>
      ) : (
        <Badge variant="warning" className="gap-1 text-[10px]">
          <XCircle className="h-3 w-3" /> not configured
        </Badge>
      )}
    </span>
  );
}

// =====================================================================
// Editable field components
// =====================================================================

interface EditableDraft {
  historyEnabled: boolean;
  chatProvider: string;
  chatModel: string;
  chatTemperature: number;
  embeddingProvider: string;
  embeddingModel: string;
  searchDefaultTopK: number;
  searchDefaultThreshold: number;
  searchEntityBoostEnabled: boolean;
  searchEntityMatchSimilarity: number;
  sessionEnabled: boolean;
  sessionMaxMessages: number;
  sessionInjectIntoExtraction: boolean;
  entityMaxLinkedMemoryIds: number;
  // Provider credentials. baseUrl is a plain text override; authToken is treated
  // as sensitive: empty string = keep existing, "" + explicit clear flag = clear.
  // The page only sends authToken when the user typed a new one.
  openaiBaseUrl: string;
  openaiAuthToken: string; // write-only buffer; '' means "no change"
  anthropicBaseUrl: string;
  anthropicAuthToken: string;
  genericBaseUrl: string;
  genericAuthToken: string;
}

const EMPTY_DRAFT: EditableDraft = {
  historyEnabled: false,
  chatProvider: 'openai',
  chatModel: '',
  chatTemperature: 0,
  embeddingProvider: 'openai',
  embeddingModel: '',
  searchDefaultTopK: 5,
  searchDefaultThreshold: 0.7,
  searchEntityBoostEnabled: false,
  searchEntityMatchSimilarity: 0.5,
  sessionEnabled: false,
  sessionMaxMessages: 10,
  sessionInjectIntoExtraction: false,
  entityMaxLinkedMemoryIds: 1000,
  openaiBaseUrl: '',
  openaiAuthToken: '',
  anthropicBaseUrl: '',
  anthropicAuthToken: '',
  genericBaseUrl: '',
  genericAuthToken: '',
};

function draftFromConfig(c: MemConfig): EditableDraft {
  return {
    historyEnabled: c.historyEnabled,
    chatProvider: c.chat.provider,
    chatModel: c.chat.model,
    chatTemperature: c.chat.temperature,
    embeddingProvider: c.embedding.provider,
    embeddingModel: c.embedding.model,
    searchDefaultTopK: c.search.defaultTopK,
    searchDefaultThreshold: c.search.defaultThreshold,
    searchEntityBoostEnabled: c.search.entityBoostEnabled,
    searchEntityMatchSimilarity: c.search.entityMatchSimilarity,
    sessionEnabled: c.session.enabled,
    sessionMaxMessages: c.session.maxMessages,
    sessionInjectIntoExtraction: c.session.injectIntoExtraction,
    entityMaxLinkedMemoryIds: c.entity.maxLinkedMemoryIds,
    openaiBaseUrl: c.providers?.openai?.baseUrl ?? '',
    openaiAuthToken: '',
    anthropicBaseUrl: c.providers?.anthropic?.baseUrl ?? '',
    anthropicAuthToken: '',
    genericBaseUrl: c.providers?.generic?.baseUrl ?? '',
    genericAuthToken: '',
  };
}

function draftDiffersFrom(d: EditableDraft, c: MemConfig): boolean {
  const base = draftFromConfig(c);
  // any field other than the auth tokens (which we always treat as "no change unless typed")
  const fieldsToCheck = (Object.keys(base) as Array<keyof EditableDraft>).filter(
    (k) => k !== 'openaiAuthToken' && k !== 'anthropicAuthToken' && k !== 'genericAuthToken',
  );
  if (fieldsToCheck.some((k) => d[k] !== base[k])) return true;
  // If user typed a new token (non-empty), that's a change.
  return Boolean(d.openaiAuthToken || d.anthropicAuthToken || d.genericAuthToken);
}

function draftToPatch(d: EditableDraft): Record<string, unknown> {
  const providers: Record<string, Record<string, unknown>> = {};
  providers.openai = { baseUrl: d.openaiBaseUrl };
  if (d.openaiAuthToken) providers.openai.authToken = d.openaiAuthToken;
  providers.anthropic = { baseUrl: d.anthropicBaseUrl };
  if (d.anthropicAuthToken) providers.anthropic.authToken = d.anthropicAuthToken;
  providers.generic = { baseUrl: d.genericBaseUrl };
  if (d.genericAuthToken) providers.generic.authToken = d.genericAuthToken;
  return {
    historyEnabled: d.historyEnabled,
    chat: {
      provider: d.chatProvider,
      model: d.chatModel,
      temperature: d.chatTemperature,
    },
    embedding: {
      provider: d.embeddingProvider,
      model: d.embeddingModel,
    },
    search: {
      defaultTopK: d.searchDefaultTopK,
      defaultThreshold: d.searchDefaultThreshold,
      entityBoostEnabled: d.searchEntityBoostEnabled,
      entityMatchSimilarity: d.searchEntityMatchSimilarity,
    },
    session: {
      enabled: d.sessionEnabled,
      maxMessages: d.sessionMaxMessages,
      injectIntoExtraction: d.sessionInjectIntoExtraction,
    },
    entity: { maxLinkedMemoryIds: d.entityMaxLinkedMemoryIds },
    providers,
  };
}

function BoolInput({
  value,
  onChange,
  disabled,
}: {
  value: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  const { tr } = useI18n();
  return (
    <label className={'inline-flex items-center gap-2 ' + (disabled ? 'opacity-50' : '')}>
      <input
        type="checkbox"
        checked={value}
        onChange={(e) => onChange(e.target.checked)}
        disabled={disabled}
        className="h-3.5 w-3.5"
      />
      <span className="text-xs text-muted-foreground">{value ? tr('memSettings.on') : tr('memSettings.off')}</span>
    </label>
  );
}

function NumberInput({
  value,
  onChange,
  min,
  max,
  step,
  disabled,
}: {
  value: number;
  onChange: (v: number) => void;
  min?: number;
  max?: number;
  step?: number;
  disabled?: boolean;
}) {
  return (
    <input
      type="number"
      value={value}
      min={min}
      max={max}
      step={step}
      disabled={disabled}
      onChange={(e) => {
        const n = Number(e.target.value);
        if (!Number.isNaN(n)) onChange(n);
      }}
      className="w-24 rounded-md border border-border bg-background px-2 py-0.5 text-xs disabled:opacity-50"
    />
  );
}

function Locked({ reason }: { reason: string }) {
  const { tr } = useI18n();
  return (
    <span className="ml-2 text-[10px] text-muted-foreground" title={reason}>
      🔒 {tr('memSettings.locked')}
    </span>
  );
}

function TextInput({
  value,
  onChange,
  placeholder,
  disabled,
  type = 'text',
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  disabled?: boolean;
  type?: string;
}) {
  return (
    <input
      type={type}
      value={value}
      placeholder={placeholder}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
      className="w-64 rounded-md border border-border bg-background px-2 py-0.5 text-xs disabled:opacity-50"
    />
  );
}

function SelectInput({
  value,
  onChange,
  options,
  disabled,
}: {
  value: string;
  onChange: (v: string) => void;
  options: string[];
  disabled?: boolean;
}) {
  return (
    <select
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
      className="rounded-md border border-border bg-background px-2 py-0.5 text-xs disabled:opacity-50"
    >
      {options.map((o) => (
        <option key={o} value={o}>
          {o}
        </option>
      ))}
    </select>
  );
}

function ProviderStatusBadge({ available }: { available: boolean }) {
  const { tr } = useI18n();
  return available ? (
    <Badge variant="default" className="ml-2 gap-1 text-[10px]">
      <CheckCircle2 className="h-3 w-3" /> {tr('memSettings.keySet')}
    </Badge>
  ) : (
    <Badge variant="outline" className="ml-2 gap-1 text-[10px] text-muted-foreground">
      <XCircle className="h-3 w-3" /> {tr('memSettings.noKey')}
    </Badge>
  );
}

function ProviderCredsBlock({
  label,
  baseUrl,
  setBaseUrl,
  authToken,
  setAuthToken,
  tokenSet,
  baseUrlPlaceholder,
}: {
  label: string;
  baseUrl: string;
  setBaseUrl: (v: string) => void;
  authToken: string;
  setAuthToken: (v: string) => void;
  tokenSet: boolean;
  baseUrlPlaceholder?: string;
}) {
  const { tr } = useI18n();
  return (
    <div className="rounded-md border border-border/40 bg-muted/20 p-3">
      <div className="mb-2 flex items-center gap-2">
        <span className="text-xs font-semibold">{label}</span>
        {tokenSet ? (
          <Badge variant="default" className="gap-1 text-[10px]">
            <CheckCircle2 className="h-3 w-3" /> {tr('memSettings.configured')}
          </Badge>
        ) : (
          <Badge variant="outline" className="gap-1 text-[10px] text-muted-foreground">
            <XCircle className="h-3 w-3" /> {tr('memSettings.notConfigured')}
          </Badge>
        )}
      </div>
      <div className="grid grid-cols-[120px_1fr] items-center gap-2 text-xs">
        <span className="text-muted-foreground">{tr('memSettings.baseUrl')}</span>
        <TextInput
          value={baseUrl}
          onChange={setBaseUrl}
          placeholder={baseUrlPlaceholder}
        />
        <span className="text-muted-foreground">{tr('memSettings.apiKey')}</span>
        <div className="flex items-center gap-2">
          <TextInput
            value={authToken}
            onChange={setAuthToken}
            placeholder={tokenSet ? tr('memSettings.tokenKeepPlaceholder') : 'sk-…'}
            type="password"
          />
          <span className="text-[10px] text-muted-foreground">
            {tokenSet ? tr('memSettings.tokenRotateHint') : tr('memSettings.tokenPasteHint')}
          </span>
        </div>
      </div>
    </div>
  );
}
