'use client';

import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Badge, Button, Card, CardContent, Input, Label } from '@nubase/ui';
import { CircleDollarSign, Plus, RefreshCw } from 'lucide-react';
import { apiFetch, type ApiError } from '@/lib/api';
import { isSuperAdmin, useSession } from '@/lib/session';

interface PriceVersion {
  id: number;
  model: string;
  normalizedModel: string;
  provider: string;
  displayName?: string | null;
  currency: string;
  inputPricePer1M: number;
  outputPricePer1M: number;
  cacheCreationPricePer1M: number;
  cacheReadPricePer1M: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  active: boolean;
}

interface DiscoveredModel {
  model: string;
  normalizedModel: string;
  provider?: string | null;
  channels: string[];
  upstreams: string[];
  billingStatus: 'PRICED' | 'UNPRICED';
}

interface PriceForm {
  model: string;
  provider: string;
  displayName: string;
  currency: string;
  inputPricePer1M: string;
  outputPricePer1M: string;
  cacheCreationPricePer1M: string;
  cacheReadPricePer1M: string;
}

const EMPTY_FORM: PriceForm = {
  model: '',
  provider: 'OPENAI',
  displayName: '',
  currency: 'USD',
  inputPricePer1M: '',
  outputPricePer1M: '',
  cacheCreationPricePer1M: '0',
  cacheReadPricePer1M: '0',
};

export default function CustomerBillingPricesPage() {
  const router = useRouter();
  const { platformKey, user, hasHydrated } = useSession();
  const superAdmin = isSuperAdmin(user);
  const [prices, setPrices] = useState<PriceVersion[]>([]);
  const [models, setModels] = useState<DiscoveredModel[]>([]);
  const [form, setForm] = useState<PriceForm>(EMPTY_FORM);
  const [loading, setLoading] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!platformKey) return;
    setLoading(true);
    setError(null);
    try {
      const auth = { apikey: platformKey, authScope: 'platform' as const };
      const [priceRows, discoveredRows] = await Promise.all([
        apiFetch<PriceVersion[]>('/ai-gateway/platform/v1/billing/prices?activeOnly=false', auth),
        apiFetch<DiscoveredModel[]>('/ai-gateway/platform/v1/billing/models/discovered', auth),
      ]);
      setPrices(priceRows);
      setModels(discoveredRows);
    } catch (err) {
      setError(errorMessage(err, 'Failed to load customer billing prices.'));
    } finally {
      setLoading(false);
    }
  }, [platformKey]);

  useEffect(() => {
    if (!hasHydrated) return;
    if (!platformKey) {
      router.replace('/login');
      return;
    }
    if (!superAdmin) {
      router.replace('/projects');
      return;
    }
    load();
  }, [hasHydrated, platformKey, superAdmin, router, load]);

  const activeCount = useMemo(() => prices.filter((price) => price.active).length, [prices]);
  const unpricedModels = useMemo(
    () => models.filter((model) => model.billingStatus === 'UNPRICED'),
    [models],
  );

  function selectModel(model: DiscoveredModel) {
    setForm((current) => ({
      ...current,
      model: model.model,
      provider: model.provider || current.provider,
      displayName: current.displayName || model.model,
    }));
  }

  async function publish(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!platformKey || publishing) return;
    setPublishing(true);
    setError(null);
    setSavedMessage(null);
    try {
      await apiFetch<PriceVersion>('/ai-gateway/platform/v1/billing/prices', {
        method: 'POST',
        apikey: platformKey,
        authScope: 'platform',
        body: {
          model: form.model.trim(),
          provider: form.provider.trim().toUpperCase(),
          displayName: form.displayName.trim() || null,
          currency: form.currency.trim().toUpperCase(),
          inputPricePer1M: numericPrice(form.inputPricePer1M),
          outputPricePer1M: numericPrice(form.outputPricePer1M),
          cacheCreationPricePer1M: numericPrice(form.cacheCreationPricePer1M),
          cacheReadPricePer1M: numericPrice(form.cacheReadPricePer1M),
        },
      });
      setForm(EMPTY_FORM);
      setSavedMessage('Price published. Existing requests keep their original price snapshot.');
      await load();
    } catch (err) {
      setError(errorMessage(err, 'Failed to publish price.'));
    } finally {
      setPublishing(false);
    }
  }

  if (!hasHydrated || !superAdmin) return null;

  return (
    <div className="mx-auto w-full max-w-7xl space-y-6 p-8">
      <header className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Customer billing prices</h1>
          <p className="text-sm text-muted-foreground">
            Platform selling prices used for managed billing across all projects. Super admins only.
          </p>
        </div>
        <Button size="sm" variant="outline" onClick={load} disabled={loading}>
          <RefreshCw className="h-3.5 w-3.5" /> {loading ? 'Refreshing…' : 'Refresh'}
        </Button>
      </header>

      {error ? <p className="text-sm text-destructive">{error}</p> : null}
      {savedMessage ? <p className="text-sm text-emerald-500">{savedMessage}</p> : null}

      <div className="grid gap-4 md:grid-cols-3">
        <SummaryCard label="Active prices" value={activeCount} />
        <SummaryCard label="Price versions" value={prices.length} />
        <SummaryCard label="Unpriced discovered models" value={unpricedModels.length} warning={unpricedModels.length > 0} />
      </div>

      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(380px,0.55fr)]">
        <Card>
          <CardContent className="p-0">
            <div className="border-b border-border px-5 py-4">
              <h2 className="font-semibold">Published price versions</h2>
              <p className="text-xs text-muted-foreground">
                Publishing a replacement closes the current version. In-flight requests retain their price snapshot.
              </p>
            </div>
            <PriceTable prices={prices} loading={loading} />
          </CardContent>
        </Card>

        <Card>
          <CardContent className="space-y-5 p-5">
            <div className="flex items-center gap-2">
              <CircleDollarSign className="h-4 w-4 text-muted-foreground" />
              <div>
                <h2 className="font-semibold">Publish a price</h2>
                <p className="text-xs text-muted-foreground">All amounts are per one million tokens.</p>
              </div>
            </div>

            {unpricedModels.length > 0 ? (
              <div className="space-y-2 rounded-md border border-amber-500/30 bg-amber-500/5 p-3">
                <p className="text-xs font-medium text-amber-700 dark:text-amber-300">
                  Models discovered from active platform upstreams without an active price
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {unpricedModels.map((model) => (
                    <button
                      key={model.normalizedModel}
                      type="button"
                      className="rounded border border-border bg-card px-2 py-1 font-mono text-[11px] hover:bg-accent"
                      onClick={() => selectModel(model)}
                      title={`Use ${model.model}`}
                    >
                      {model.model}
                    </button>
                  ))}
                </div>
              </div>
            ) : null}

            <form className="space-y-4" onSubmit={publish}>
              <Field label="Model" htmlFor="billing-price-model">
                <Input
                  id="billing-price-model"
                  required
                  maxLength={160}
                  list="billing-models"
                  value={form.model}
                  onChange={(event) => setForm({ ...form, model: event.target.value })}
                  placeholder="example-model"
                />
                <datalist id="billing-models">
                  {models.map((model) => <option key={model.normalizedModel} value={model.model} />)}
                </datalist>
              </Field>

              <div className="grid grid-cols-2 gap-3">
                <Field label="Provider" htmlFor="billing-price-provider">
                  <select
                    id="billing-price-provider"
                    required
                    className={selectClass}
                    value={form.provider}
                    onChange={(event) => setForm({ ...form, provider: event.target.value })}
                  >
                    <option value="OPENAI">OPENAI</option>
                    <option value="CLAUDE">CLAUDE</option>
                  </select>
                </Field>
                <Field label="Currency" htmlFor="billing-price-currency">
                  <Input
                    id="billing-price-currency"
                    required
                    minLength={3}
                    maxLength={8}
                    pattern="[A-Za-z]{3,8}"
                    value={form.currency}
                    onChange={(event) => setForm({ ...form, currency: event.target.value })}
                  />
                </Field>
              </div>

              <Field label="Display name" htmlFor="billing-price-display-name" hint="Optional label shown to operators.">
                <Input
                  id="billing-price-display-name"
                  maxLength={160}
                  value={form.displayName}
                  onChange={(event) => setForm({ ...form, displayName: event.target.value })}
                />
              </Field>

              <div className="grid grid-cols-2 gap-3">
                <PriceInput label="Input" value={form.inputPricePer1M} onChange={(value) => setForm({ ...form, inputPricePer1M: value })} required />
                <PriceInput label="Output" value={form.outputPricePer1M} onChange={(value) => setForm({ ...form, outputPricePer1M: value })} required />
                <PriceInput label="Cache creation" value={form.cacheCreationPricePer1M} onChange={(value) => setForm({ ...form, cacheCreationPricePer1M: value })} />
                <PriceInput label="Cache read" value={form.cacheReadPricePer1M} onChange={(value) => setForm({ ...form, cacheReadPricePer1M: value })} />
              </div>

              <Button className="w-full" type="submit" variant="brand" disabled={publishing}>
                <Plus className="h-3.5 w-3.5" /> {publishing ? 'Publishing…' : 'Publish price version'}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function SummaryCard({ label, value, warning = false }: { label: string; value: number; warning?: boolean }) {
  return (
    <Card>
      <CardContent className="p-5">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className={warning ? 'mt-1 text-2xl font-semibold text-amber-500' : 'mt-1 text-2xl font-semibold'}>{value}</p>
      </CardContent>
    </Card>
  );
}

function PriceTable({ prices, loading }: { prices: PriceVersion[]; loading: boolean }) {
  if (loading && prices.length === 0) {
    return <p className="p-5 text-sm text-muted-foreground">Loading…</p>;
  }
  if (prices.length === 0) {
    return <p className="p-5 text-sm text-muted-foreground">No customer billing prices published.</p>;
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[850px] text-sm">
        <thead className="border-b border-border text-xs text-muted-foreground">
          <tr>
            {['Model', 'Provider', 'Input / 1M', 'Output / 1M', 'Cache create / read', 'Effective period', 'Status'].map((header) => (
              <th key={header} className="px-4 py-2 text-left font-medium">{header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {prices.map((price) => (
            <tr key={price.id} className="border-b border-border/40 last:border-b-0">
              <td className="px-4 py-3">
                <div className="font-medium">{price.displayName || price.model}</div>
                <div className="font-mono text-[11px] text-muted-foreground">{price.model}</div>
              </td>
              <td className="px-4 py-3">{price.provider}</td>
              <td className="px-4 py-3 font-mono">{money(price.inputPricePer1M, price.currency)}</td>
              <td className="px-4 py-3 font-mono">{money(price.outputPricePer1M, price.currency)}</td>
              <td className="px-4 py-3 font-mono text-xs">
                {money(price.cacheCreationPricePer1M, price.currency)} / {money(price.cacheReadPricePer1M, price.currency)}
              </td>
              <td className="px-4 py-3 text-xs text-muted-foreground">
                <div>{formatDate(price.effectiveFrom)}</div>
                <div>{price.effectiveTo ? `to ${formatDate(price.effectiveTo)}` : 'current'}</div>
              </td>
              <td className="px-4 py-3">
                <Badge variant={price.active ? 'success' : 'outline'}>{price.active ? 'active' : 'historical'}</Badge>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Field({ label, htmlFor, hint, children }: { label: string; htmlFor?: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
      {hint ? <p className="text-[11px] text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

function PriceInput({ label, value, onChange, required = false }: { label: string; value: string; onChange: (value: string) => void; required?: boolean }) {
  const id = `billing-price-${label.toLowerCase().replaceAll(' ', '-')}`;
  return (
    <Field label={label} htmlFor={id}>
      <Input
        id={id}
        type="number"
        min="0"
        step="0.00000001"
        required={required}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </Field>
  );
}

function numericPrice(value: string): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) {
    throw new Error('Prices must be non-negative numbers.');
  }
  return parsed;
}

function money(value: number, currency: string): string {
  return `${currency} ${Number(value ?? 0).toFixed(6)}`;
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error) return error.message;
  return (error as ApiError | undefined)?.message || fallback;
}

const selectClass =
  'flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm outline-none focus-visible:ring-2 focus-visible:ring-ring/25';
