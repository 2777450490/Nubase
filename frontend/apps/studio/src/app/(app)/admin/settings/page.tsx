'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Mail, HardDrive, Save, RefreshCw } from 'lucide-react';
import { Button, Card, CardContent } from '@nubase/ui';
import { apiFetch, type ApiError } from '@/lib/api';
import { useSession, isSuperAdmin } from '@/lib/session';
import { useI18n } from '@/lib/i18n';

/**
 * Platform-wide runtime settings. Super-admin only.
 *
 * Each section reads/writes one category via /auth/v1/admin/platform/settings/{category}.
 * Sensitive keys come back as { set: boolean } from the server — never the actual value.
 * Leaving a sensitive field blank in the PUT body means "keep existing"; an empty string
 * clears it.
 */

interface CategoryResponse {
  category: string;
  values: Record<string, unknown>;
}

// =================== SMTP ===================

interface SmtpForm {
  host: string;
  port: string;
  username: string;
  password: string;
  from_address: string;
  from_name: string;
  starttls_enabled: string; // "true" | "false"
}

const EMPTY_SMTP: SmtpForm = {
  host: '',
  port: '587',
  username: '',
  password: '',
  from_address: '',
  from_name: '',
  starttls_enabled: 'true',
};

// =================== R2 ===================

interface R2Form {
  endpoint: string;
  region: string;
  access_key_id: string;
  secret_access_key: string;
  global_bucket: string;
  public_url: string;
}

const EMPTY_R2: R2Form = {
  endpoint: '',
  region: 'auto',
  access_key_id: '',
  secret_access_key: '',
  global_bucket: '',
  public_url: '',
};

export default function PlatformSettingsPage() {
  const { tr } = useI18n();
  const router = useRouter();
  const { platformKey, user, hasHydrated } = useSession();
  const superAdmin = isSuperAdmin(user);

  const [smtp, setSmtp] = useState<SmtpForm>(EMPTY_SMTP);
  const [smtpPasswordSet, setSmtpPasswordSet] = useState(false);

  const [r2, setR2] = useState<R2Form>(EMPTY_R2);
  const [r2AccessKeySet, setR2AccessKeySet] = useState(false);
  const [r2SecretSet, setR2SecretSet] = useState(false);

  const [loading, setLoading] = useState(false);
  const [savingSection, setSavingSection] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!platformKey) return;
    setLoading(true);
    setError(null);
    try {
      const [smtpRes, r2Res] = await Promise.all([
        apiFetch<CategoryResponse>('/auth/v1/admin/platform/settings/smtp', {
          apikey: platformKey,
        }),
        apiFetch<CategoryResponse>('/auth/v1/admin/platform/settings/storage_r2', {
          apikey: platformKey,
        }),
      ]);
      const sv = smtpRes.values ?? {};
      setSmtp({
        host: stringOr(sv.host, ''),
        port: stringOr(sv.port, '587'),
        username: stringOr(sv.username, ''),
        password: '',
        from_address: stringOr(sv.from_address, ''),
        from_name: stringOr(sv.from_name, ''),
        starttls_enabled: stringOr(sv.starttls_enabled, 'true'),
      });
      setSmtpPasswordSet(isSet(sv.password));

      const rv = r2Res.values ?? {};
      setR2({
        endpoint: stringOr(rv.endpoint, ''),
        region: stringOr(rv.region, 'auto'),
        access_key_id: '',
        secret_access_key: '',
        global_bucket: stringOr(rv.global_bucket, ''),
        public_url: stringOr(rv.public_url, ''),
      });
      setR2AccessKeySet(isSet(rv.access_key_id));
      setR2SecretSet(isSet(rv.secret_access_key));
    } catch (err) {
      setError((err as ApiError).message ?? tr('adminSettings.loadFailed'));
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

  async function saveSmtp() {
    if (!platformKey) return;
    setSavingSection('smtp');
    setError(null);
    setSavedMessage(null);
    const body: Record<string, string> = {
      host: smtp.host,
      port: smtp.port,
      username: smtp.username,
      from_address: smtp.from_address,
      from_name: smtp.from_name,
      starttls_enabled: smtp.starttls_enabled,
    };
    if (smtp.password.length > 0) body.password = smtp.password;
    try {
      const res = await apiFetch<CategoryResponse>('/auth/v1/admin/platform/settings/smtp', {
        method: 'PUT',
        body,
        apikey: platformKey,
      });
      setSavedMessage(tr('adminSettings.smtpSaved'));
      setSmtp((cur) => ({ ...cur, password: '' }));
      setSmtpPasswordSet(isSet((res.values ?? {}).password));
    } catch (err) {
      setError((err as ApiError).message ?? tr('adminSettings.saveFailed'));
    } finally {
      setSavingSection(null);
    }
  }

  async function saveR2() {
    if (!platformKey) return;
    setSavingSection('r2');
    setError(null);
    setSavedMessage(null);
    const body: Record<string, string> = {
      endpoint: r2.endpoint,
      region: r2.region,
      global_bucket: r2.global_bucket,
      public_url: r2.public_url,
    };
    if (r2.access_key_id.length > 0) body.access_key_id = r2.access_key_id;
    if (r2.secret_access_key.length > 0) body.secret_access_key = r2.secret_access_key;
    try {
      const res = await apiFetch<CategoryResponse>(
        '/auth/v1/admin/platform/settings/storage_r2',
        { method: 'PUT', body, apikey: platformKey },
      );
      setSavedMessage(tr('adminSettings.r2Saved'));
      setR2((cur) => ({ ...cur, access_key_id: '', secret_access_key: '' }));
      setR2AccessKeySet(isSet((res.values ?? {}).access_key_id));
      setR2SecretSet(isSet((res.values ?? {}).secret_access_key));
    } catch (err) {
      setError((err as ApiError).message ?? tr('adminSettings.saveFailed'));
    } finally {
      setSavingSection(null);
    }
  }

  if (!hasHydrated || !superAdmin) return null;

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-8">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{tr('adminSettings.title')}</h1>
          <p className="text-sm text-muted-foreground">
            {tr('adminSettings.desc')}
          </p>
        </div>
        <Button size="sm" variant="outline" onClick={load} disabled={loading}>
          <RefreshCw className="h-3.5 w-3.5" /> {tr('adminSettings.refresh')}
        </Button>
      </header>

      {error ? <p className="text-sm text-destructive">{error}</p> : null}
      {savedMessage ? <p className="text-sm text-emerald-500">{savedMessage}</p> : null}

      <Card>
        <CardContent className="space-y-4 p-6">
          <div className="flex items-center gap-2 border-b border-border pb-3">
            <Mail className="h-4 w-4 text-muted-foreground" />
            <h2 className="text-lg font-semibold">{tr('adminSettings.smtpTitle')}</h2>
          </div>

          <Field label={tr('adminSettings.fieldHost')}>
            <input
              className={inputClass}
              placeholder="smtp.example.com"
              value={smtp.host}
              onChange={(e) => setSmtp({ ...smtp, host: e.target.value })}
              disabled={loading}
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label={tr('adminSettings.fieldPort')}>
              <input
                className={inputClass}
                placeholder="587"
                value={smtp.port}
                onChange={(e) => setSmtp({ ...smtp, port: e.target.value })}
                disabled={loading}
                inputMode="numeric"
              />
            </Field>
            <Field label={tr('adminSettings.fieldStarttls')}>
              <select
                className={inputClass}
                value={smtp.starttls_enabled}
                onChange={(e) => setSmtp({ ...smtp, starttls_enabled: e.target.value })}
                disabled={loading}
              >
                <option value="true">{tr('adminSettings.starttlsEnabled')}</option>
                <option value="false">{tr('adminSettings.starttlsDisabled')}</option>
              </select>
            </Field>
          </div>

          <Field label={tr('adminSettings.fieldUsername')}>
            <input
              className={inputClass}
              placeholder="postmaster@example.com"
              value={smtp.username}
              onChange={(e) => setSmtp({ ...smtp, username: e.target.value })}
              disabled={loading}
            />
          </Field>

          <Field
            label={tr('adminSettings.fieldPassword')}
            hint={smtpPasswordSet ? tr('adminSettings.passwordSetHint') : tr('adminSettings.passwordNotSet')}
          >
            <input
              className={inputClass}
              type="password"
              placeholder={smtpPasswordSet ? '••••••••' : ''}
              value={smtp.password}
              onChange={(e) => setSmtp({ ...smtp, password: e.target.value })}
              disabled={loading}
              autoComplete="new-password"
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label={tr('adminSettings.fieldFromAddress')}>
              <input
                className={inputClass}
                placeholder="noreply@example.com"
                value={smtp.from_address}
                onChange={(e) => setSmtp({ ...smtp, from_address: e.target.value })}
                disabled={loading}
              />
            </Field>
            <Field label={tr('adminSettings.fieldFromName')}>
              <input
                className={inputClass}
                placeholder="Nubase"
                value={smtp.from_name}
                onChange={(e) => setSmtp({ ...smtp, from_name: e.target.value })}
                disabled={loading}
              />
            </Field>
          </div>

          <div className="flex justify-end pt-2">
            <Button
              onClick={saveSmtp}
              disabled={savingSection !== null || loading}
              variant="brand"
            >
              <Save className="h-3.5 w-3.5" />{' '}
              {savingSection === 'smtp' ? tr('adminSettings.saving') : tr('adminSettings.saveSmtp')}
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-4 p-6">
          <div className="flex items-center gap-2 border-b border-border pb-3">
            <HardDrive className="h-4 w-4 text-muted-foreground" />
            <h2 className="text-lg font-semibold">{tr('adminSettings.r2Title')}</h2>
          </div>
          <p className="text-xs text-muted-foreground">
            {tr('adminSettings.r2DescPrefix')}{' '}
            <code>https://&lt;account&gt;.r2.cloudflarestorage.com</code>{' '}
            {tr('adminSettings.r2DescMid')}{' '}
            <code>auto</code>
            {tr('adminSettings.r2DescSuffix')}
          </p>

          <Field label={tr('adminSettings.fieldEndpoint')}>
            <input
              className={inputClass}
              placeholder="https://<account>.r2.cloudflarestorage.com"
              value={r2.endpoint}
              onChange={(e) => setR2({ ...r2, endpoint: e.target.value })}
              disabled={loading}
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label={tr('adminSettings.fieldRegion')}>
              <input
                className={inputClass}
                placeholder="auto"
                value={r2.region}
                onChange={(e) => setR2({ ...r2, region: e.target.value })}
                disabled={loading}
              />
            </Field>
            <Field label={tr('adminSettings.fieldGlobalBucket')}>
              <input
                className={inputClass}
                placeholder="nubase-storage"
                value={r2.global_bucket}
                onChange={(e) => setR2({ ...r2, global_bucket: e.target.value })}
                disabled={loading}
              />
            </Field>
          </div>

          <Field
            label={tr('adminSettings.fieldAccessKey')}
            hint={r2AccessKeySet ? tr('adminSettings.accessKeySetHint') : tr('adminSettings.accessKeyNotSet')}
          >
            <input
              className={inputClass}
              type="password"
              placeholder={r2AccessKeySet ? '••••••••' : ''}
              value={r2.access_key_id}
              onChange={(e) => setR2({ ...r2, access_key_id: e.target.value })}
              disabled={loading}
              autoComplete="off"
            />
          </Field>

          <Field
            label={tr('adminSettings.fieldSecretKey')}
            hint={r2SecretSet ? tr('adminSettings.secretSetHint') : tr('adminSettings.secretNotSet')}
          >
            <input
              className={inputClass}
              type="password"
              placeholder={r2SecretSet ? '••••••••' : ''}
              value={r2.secret_access_key}
              onChange={(e) => setR2({ ...r2, secret_access_key: e.target.value })}
              disabled={loading}
              autoComplete="new-password"
            />
          </Field>

          <Field label={tr('adminSettings.fieldPublicUrl')}>
            <input
              className={inputClass}
              placeholder="https://files.example.com"
              value={r2.public_url}
              onChange={(e) => setR2({ ...r2, public_url: e.target.value })}
              disabled={loading}
            />
          </Field>

          <div className="flex justify-end pt-2">
            <Button
              onClick={saveR2}
              disabled={savingSection !== null || loading}
              variant="brand"
            >
              <Save className="h-3.5 w-3.5" />{' '}
              {savingSection === 'r2' ? tr('adminSettings.saving') : tr('adminSettings.saveR2')}
            </Button>
          </div>
        </CardContent>
      </Card>

      <p className="text-xs text-muted-foreground">
        {tr('adminSettings.morePrefix')}{' '}
        <code>PlatformSettingsService</code>
        {tr('adminSettings.moreSuffix')}
      </p>
    </div>
  );
}

const inputClass =
  'w-full rounded-md border border-border bg-background px-3 py-2 text-sm outline-none focus:border-foreground/40';

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block space-y-1">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      {children}
      {hint ? <span className="block text-[11px] text-muted-foreground">{hint}</span> : null}
    </label>
  );
}

function stringOr(v: unknown, fallback: string): string {
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return fallback;
}

function isSet(v: unknown): boolean {
  return Boolean((v as { set?: boolean } | undefined)?.set);
}
