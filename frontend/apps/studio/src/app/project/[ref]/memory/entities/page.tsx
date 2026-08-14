'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import {
  RefreshCw,
  ChevronDown,
  ChevronRightIcon,
  Trash2,
  ChevronLeft as PageLeft,
  ChevronRight as PageRight,
  Tag,
} from 'lucide-react';
import {
  Button,
  Input,
  Label,
  Badge,
  Card,
  CardContent,
  Dialog,
  DialogHeader,
  DialogBody,
  DialogFooter,
} from '@nubase/ui';
import { apiFetch, type ApiError } from '@/lib/api';
import { useSession, isProjectReady } from '@/lib/session';
import { NotProvisioned } from '@/components/not-provisioned';
import { MemorySubNav } from '../_components/sub-nav';
import { useProjectRef } from '@/lib/route-params';
import { useI18n } from '@/lib/i18n';
import type { EntityItem, PagedResponse } from '@/lib/mem-types';

const PAGE_SIZE = 50;
const ENTITY_TYPES = ['', 'person', 'location', 'organization', 'product', 'event', 'food', 'date', 'other'];

export default function EntitiesPage({ params }: { params: { ref: string } }) {
  const { project } = useSession();
  const projectRef = useProjectRef(params.ref);
  const ready = isProjectReady(project);
  if (!ready) {
    return <NotProvisioned projectRef={project?.ref ?? projectRef} initStatus={project?.initStatus} />;
  }
  return <EntitiesInner projectRef={projectRef} />;
}

function EntitiesInner({ projectRef }: { projectRef: string }) {
  const { tr } = useI18n();
  const trLoose = (key: string, values?: Record<string, string | number>) => tr(key as any, values);
  const { project } = useSession();
  const apikey = project!.apikey;

  const [items, setItems] = useState<EntityItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [typeFilter, setTypeFilter] = useState('');
  const [userIdFilter, setUserIdFilter] = useState('');
  const [appliedUserId, setAppliedUserId] = useState('');

  const [expanded, setExpanded] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<EntityItem | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const qs = new URLSearchParams({ page: String(page), pageSize: String(PAGE_SIZE) });
      if (typeFilter) qs.set('type', typeFilter);
      if (appliedUserId) qs.set('userId', appliedUserId);
      const res = await apiFetch<PagedResponse<EntityItem>>(`/mem/v1/entities?${qs}`, { apikey });
      setItems(res.items ?? []);
      setTotal(res.total ?? 0);
    } catch (err) {
      setError((err as ApiError).message ?? trLoose('entities.loadFailed'));
      setItems([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [apikey, page, typeFilter, appliedUserId]);

  useEffect(() => { load(); }, [load]);

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const performDelete = async () => {
    if (!deleting) return;
    const id = deleting.id;
    setDeleting(null);
    try {
      await apiFetch(`/mem/v1/entities/${id}`, { apikey, method: 'DELETE' });
      await load();
    } catch (err) {
      setError((err as ApiError).message ?? trLoose('entities.deleteFailed'));
    }
  };

  return (
    <div className="flex h-full flex-col">
      <MemorySubNav projectRef={projectRef} active="entities" />

      {/* Header */}
      <header className="flex items-center justify-between border-b border-border px-4 py-2">
        <div className="flex items-center gap-3">
          <h2 className="text-sm font-semibold">
            <Tag className="mr-1.5 inline h-3.5 w-3.5" /> {tr('entities.title')}
          </h2>
          <Badge variant="outline">{tr('entities.total', { n: total })}</Badge>
        </div>
        <Button size="sm" variant="outline" onClick={load}>
          <RefreshCw className="h-3.5 w-3.5" /> {tr('entities.refresh')}
        </Button>
      </header>

      {/* Filter strip */}
      <div className="flex items-center gap-3 border-b border-border px-4 py-2">
        <div className="flex items-center gap-2">
          <Label className="text-xs">{tr('entities.type')}</Label>
          <select
            value={typeFilter}
            onChange={(e) => { setTypeFilter(e.target.value); setPage(1); }}
            className="h-7 rounded-md border border-input bg-background px-2 text-xs"
          >
            {ENTITY_TYPES.map((t) => (
              <option key={t} value={t}>{t || trLoose('entities.allTypes')}</option>
            ))}
          </select>
        </div>
        <div className="flex items-center gap-2">
          <Label className="text-xs">{tr('entities.userId')}</Label>
          <Input
            value={userIdFilter}
            onChange={(e) => setUserIdFilter(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') { setAppliedUserId(userIdFilter.trim()); setPage(1); }
            }}
            placeholder="UUID"
            className="h-7 w-64 text-xs"
          />
          <Button
            size="sm"
            variant="outline"
            onClick={() => { setAppliedUserId(userIdFilter.trim()); setPage(1); }}
          >
            Apply
          </Button>
          {appliedUserId && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => { setUserIdFilter(''); setAppliedUserId(''); setPage(1); }}
            >
              Clear
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div className="border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-xs text-destructive">
          {error}
        </div>
      )}

      {/* Table */}
      <div className="flex-1 overflow-auto">
        <table className="w-full text-xs">
          <thead className="sticky top-0 bg-card text-left text-muted-foreground">
            <tr className="border-b border-border">
              <th className="w-8 px-3 py-2" />
              <th className="px-3 py-2">{tr('entities.colText')}</th>
              <th className="w-32 px-3 py-2">{tr('entities.colType')}</th>
              <th className="w-48 px-3 py-2">{tr('entities.colUser')}</th>
              <th className="w-24 px-3 py-2">{tr('entities.colLinks')}</th>
              <th className="w-32 px-3 py-2">{tr('entities.colCreated')}</th>
              <th className="w-16 px-3 py-2" />
            </tr>
          </thead>
          <tbody>
            {loading && items.length === 0 ? (
              <tr><td colSpan={7} className="px-3 py-8 text-center text-muted-foreground">{tr('entities.loading')}</td></tr>
            ) : items.length === 0 ? (
              <tr><td colSpan={7} className="px-3 py-8 text-center text-muted-foreground">{tr('entities.empty')}</td></tr>
            ) : (
              items.map((e) => {
                const isOpen = expanded === e.id;
                const links = e.linkedMemoryIds ?? [];
                return (
                  <>
                    <tr
                      key={e.id}
                      className="cursor-pointer border-b border-border/50 hover:bg-accent/30"
                      onClick={() => setExpanded(isOpen ? null : e.id)}
                    >
                      <td className="px-3 py-2">
                        {isOpen
                          ? <ChevronDown className="h-3.5 w-3.5" />
                          : <ChevronRightIcon className="h-3.5 w-3.5" />}
                      </td>
                      <td className="px-3 py-2 font-medium">{e.text}</td>
                      <td className="px-3 py-2">
                        {e.entityType
                          ? <Badge variant="outline" className="text-[10px]">{e.entityType}</Badge>
                          : <span className="text-muted-foreground">—</span>}
                      </td>
                      <td className="px-3 py-2 font-mono text-[10px] text-muted-foreground">
                        {e.userId ? truncateUuid(e.userId) : '—'}
                      </td>
                      <td className="px-3 py-2 text-muted-foreground">{links.length}</td>
                      <td className="px-3 py-2 text-[10px] text-muted-foreground">{formatDate(e.createdAt)}</td>
                      <td className="px-3 py-2">
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={(ev) => { ev.stopPropagation(); setDeleting(e); }}
                        >
                          <Trash2 className="h-3.5 w-3.5 text-destructive" />
                        </Button>
                      </td>
                    </tr>
                    {isOpen && (
                      <tr key={e.id + '-expand'} className="border-b border-border/50 bg-accent/10">
                        <td />
                        <td colSpan={6} className="px-3 py-3">
                          <div className="space-y-2">
                            <div className="text-[10px] uppercase tracking-wider text-muted-foreground">
                              {tr('entities.linkedMemories', { n: links.length })}
                            </div>
                            {links.length === 0 ? (
                              <p className="text-xs text-muted-foreground">
                                {tr('entities.noLinked')}
                              </p>
                            ) : (
                              <ul className="space-y-1">
                                {links.map((mid) => (
                                  <li key={mid}>
                                    <Link
                                      href={`/project/${projectRef}/memory/${mid}`}
                                      className="inline-block font-mono text-[11px] text-foreground hover:underline"
                                    >
                                      {mid}
                                    </Link>
                                  </li>
                                ))}
                              </ul>
                            )}
                          </div>
                        </td>
                      </tr>
                    )}
                  </>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <footer className="flex items-center justify-between border-t border-border px-4 py-2 text-xs">
        <span className="text-muted-foreground">
          {total === 0 ? trLoose('entities.ofEmpty') : trLoose('entities.of', { start: (page - 1) * PAGE_SIZE + 1, end: Math.min(page * PAGE_SIZE, total), total })}
        </span>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="outline" disabled={page <= 1} onClick={() => setPage(page - 1)}>
            <PageLeft className="h-3.5 w-3.5" />
          </Button>
          <span className="px-2">{page} / {pageCount}</span>
          <Button size="sm" variant="outline" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>
            <PageRight className="h-3.5 w-3.5" />
          </Button>
        </div>
      </footer>

      {/* Delete confirm */}
      <Dialog open={!!deleting} onOpenChange={(v) => !v && setDeleting(null)}>
        <DialogHeader>{tr('entities.deleteTitle')}</DialogHeader>
        <DialogBody>
          {deleting && (
            <>
              <p className="text-sm">
                {tr('entities.deleteConfirmPrefix')} <span className="font-medium">&ldquo;{deleting.text}&rdquo;</span>{tr('entities.deleteConfirmSuffix')}
              </p>
              <p className="mt-2 text-xs text-muted-foreground">
                {tr('entities.deleteDescPrefix')}
                <code className="mx-1 rounded bg-muted/40 px-1">add</code>
                {tr('entities.deleteDescSuffix')}
              </p>
            </>
          )}
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={() => setDeleting(null)}>{tr('entities.cancel')}</Button>
          <Button variant="destructive" onClick={performDelete}>{tr('entities.delete')}</Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}

function truncateUuid(u: string): string {
  return u.length > 8 ? u.slice(0, 8) + '…' : u;
}

function formatDate(s?: string | null): string {
  if (!s) return '—';
  try {
    return new Date(s).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' });
  } catch {
    return s;
  }
}
