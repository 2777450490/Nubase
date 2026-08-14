'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Button, Card, CardHeader, CardTitle, CardDescription, CardContent } from '@nubase/ui';
import { Table2, Terminal, Users, HardDrive, Copy, Check } from 'lucide-react';
import { useSession, isProjectReady } from '@/lib/session';
import { NotProvisioned } from '@/components/not-provisioned';
import { API_BASE } from '@/lib/api';
import { useProjectRef } from '@/lib/route-params';
import { useI18n } from '@/lib/i18n';

const QUICK_LINKS = (tr: (key: string, values?: Record<string, string | number>) => string) => [
  { label: tr('projectHome.browseTables'), href: 'editor', icon: Table2 },
  { label: tr('projectHome.runSql'), href: 'sql', icon: Terminal },
  { label: tr('projectHome.manageUsers'), href: 'auth', icon: Users },
  { label: tr('projectHome.browseStorage'), href: 'storage', icon: HardDrive },
];

export default function ProjectHome({ params }: { params: { ref: string } }) {
  const { tr } = useI18n();
  const trLoose = (key: string, values?: Record<string, string | number>) => tr(key as any, values);
  const project = useSession((s) => s.project);
  const projectRef = useProjectRef(params.ref);
  const ready = isProjectReady(project);
  const name = project?.name ?? projectRef;

  // In production the Studio bundle is built with NEXT_PUBLIC_NUBASE_API_URL="" so API calls are
  // same-origin relative — which makes API_BASE an empty string. Show the real public origin instead.
  const [origin, setOrigin] = useState('');
  useEffect(() => {
    if (typeof window !== 'undefined') setOrigin(window.location.origin);
  }, []);
  const apiUrl = API_BASE || origin;

  if (!ready) {
    return (
      <div className="space-y-6 p-8">
        <header>
          <p className="text-xs uppercase tracking-wide text-muted-foreground">{tr('projectHome.section')}</p>
          <h1 className="text-2xl font-semibold tracking-tight">{name}</h1>
        </header>
        <NotProvisioned projectRef={projectRef} initStatus={project?.initStatus} />
      </div>
    );
  }

  return (
    <div className="space-y-6 p-8">
      <header>
        <p className="text-xs uppercase tracking-wide text-muted-foreground">{tr('projectHome.section')}</p>
        <h1 className="text-2xl font-semibold tracking-tight">{name}</h1>
      </header>

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {QUICK_LINKS(trLoose).map((q) => {
          const Icon = q.icon;
          return (
            <Link key={q.href} href={`/project/${projectRef}/${q.href}`}>
              <Card className="h-full transition hover:border-foreground/30">
                <CardContent className="flex flex-col gap-2 p-5">
                  <Icon className="h-5 w-5 text-muted-foreground" />
                  <span className="text-sm font-medium">{q.label}</span>
                </CardContent>
              </Card>
            </Link>
          );
        })}
      </section>

      <section className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tr('projectHome.connection')}</CardTitle>
            <CardDescription>{tr('projectHome.connectionDesc')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-xs">
            <CopyField label={tr('projectHome.url')} value={apiUrl} mono />
            <CopyField label={tr('projectHome.serviceKey')} value={project?.apikey ?? ''} mono masked />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{tr('projectHome.recentActivity')}</CardTitle>
            <CardDescription>{tr('projectHome.activityDesc')}</CardDescription>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">{tr('projectHome.noActivity')}</CardContent>
        </Card>
      </section>
    </div>
  );
}

/** A label + value row with a copy-to-clipboard button. `masked` shows a truncated preview. */
function CopyField({
  label,
  value,
  mono,
  masked,
}: {
  label: string;
  value: string;
  mono?: boolean;
  masked?: boolean;
}) {
  const [copied, setCopied] = useState(false);
  const { tr } = useI18n();
  const trLoose = (key: string, values?: Record<string, string | number>) => tr(key as any, values);
  async function copy() {
    if (!value) return;
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard blocked (e.g. insecure context) — silently ignore */
    }
  }

  const display = !value ? '—' : masked ? `${value.slice(0, 16)}…${value.slice(-6)}` : value;

  return (
    <div>
      <p className="text-muted-foreground">{label}</p>
      <div className="flex items-center gap-2">
        <p className={`min-w-0 flex-1 truncate rounded-md bg-muted px-3 py-2 ${mono ? 'font-mono' : ''}`}>
          {display}
        </p>
        <Button
          size="icon"
          variant="ghost"
          onClick={copy}
          disabled={!value}
          aria-label={`${trLoose('projectHome.copy', { label })}`}
        >
          {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
        </Button>
      </div>
    </div>
  );
}
