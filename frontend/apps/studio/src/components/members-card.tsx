'use client';

import { useCallback, useEffect, useState } from 'react';
import { Trash2, UserPlus } from 'lucide-react';
import {
  Button,
  Input,
  Label,
  Badge,
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  Dialog,
  DialogHeader,
  DialogBody,
  DialogFooter,
  useToast,
} from '@nubase/ui';
import { apiFetch, type ApiError } from '@/lib/api';
import { useSession } from '@/lib/session';
import { useI18n } from '@/lib/i18n';

interface Member {
  userId: string;
  email?: string | null;
  fullName?: string | null;
  role: string;
  addedAt?: string | null;
}

export function MembersCard({ projectRef }: { projectRef: string }) {
  const { tr } = useI18n();
  const { toast } = useToast();
  const { platformKey, user } = useSession();
  const [members, setMembers] = useState<Member[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [addingOpen, setAddingOpen] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!platformKey) return;
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch<Member[]>(
        `/auth/v1/admin/projects/${encodeURIComponent(projectRef)}/members`,
        { apikey: platformKey }
      );
      setMembers(res);
    } catch (err) {
      setError((err as ApiError).message ?? tr('members.empty'));
    } finally {
      setLoading(false);
    }
  }, [platformKey, projectRef]);

  useEffect(() => {
    load();
  }, [load]);

  async function remove(userId: string) {
    if (!platformKey) return;
    if (!confirm(tr('members.remove'))) return;
    setRemoving(userId);
    try {
      await apiFetch(
        `/auth/v1/admin/projects/${encodeURIComponent(projectRef)}/members/${encodeURIComponent(userId)}`,
        { method: 'DELETE', apikey: platformKey }
      );
      toast({ variant: 'success', message: tr('members.loading') });
      await load();
    } catch (err) {
      const msg = (err as ApiError).message ?? tr('members.adding');
      setError(msg);
      toast({ variant: 'error', message: msg });
    } finally {
      setRemoving(null);
    }
  }

  return (
    <>
      <Card>
        <CardHeader>
          <div className="flex items-start justify-between gap-3">
            <div>
              <CardTitle className="text-base">{tr('members.title')}</CardTitle>
              <CardDescription>{tr('members.desc')}</CardDescription>
            </div>
            <Button size="sm" onClick={() => setAddingOpen(true)}>
              <UserPlus className="h-3.5 w-3.5" /> {tr('members.add')}
            </Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-2">
          {error ? <p className="text-xs text-destructive">{error}</p> : null}
          {loading ? (
            <p className="text-sm text-muted-foreground">{tr('members.loading')}</p>
          ) : members.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              {tr('members.empty')}
            </p>
          ) : (
            <ul className="divide-y divide-border">
              {members.map((m) => {
                const isYou = user?.id === m.userId;
                return (
                  <li key={m.userId} className="flex items-center justify-between gap-3 py-2">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium">
                        {m.email ?? m.userId}
                        {isYou ? <span className="ml-2 text-xs text-muted-foreground">{tr('members.you')}</span> : null}
                      </p>
                      {m.fullName ? <p className="truncate text-xs text-muted-foreground">{m.fullName}</p> : null}
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge variant={m.role === 'owner' ? 'success' : 'outline'}>{m.role}</Badge>
                      <button
                        onClick={() => remove(m.userId)}
                        className="rounded-md p-1.5 text-muted-foreground hover:bg-destructive/15 hover:text-destructive"
                        title={isYou ? tr('members.leave') : tr('members.remove')}
                        disabled={removing === m.userId}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </CardContent>
      </Card>

      <AddMemberDialog
        open={addingOpen}
        projectRef={projectRef}
        onClose={() => setAddingOpen(false)}
        onAdded={() => {
          setAddingOpen(false);
          load();
        }}
      />
    </>
  );
}

function AddMemberDialog({ open, projectRef, onClose, onAdded }: {
  open: boolean;
  projectRef: string;
  onClose: () => void;
  onAdded: () => void;
}) {
  const { tr } = useI18n();
  const { toast } = useToast();
  const { platformKey } = useSession();
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('member');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setEmail('');
      setRole('member');
      setError(null);
    }
  }, [open]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!platformKey) return;
    setSubmitting(true);
    setError(null);
    try {
      await apiFetch(`/auth/v1/admin/projects/${encodeURIComponent(projectRef)}/members`, {
        method: 'POST',
        body: { email, role },
        apikey: platformKey,
      });
      toast({ variant: 'success', message: `${email} added as ${role}.` });
      onAdded();
    } catch (err) {
      const msg = (err as ApiError).message ?? tr('members.adding');
      setError(msg);
      toast({ variant: 'error', message: msg });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose}>
      <DialogHeader
        title={tr('members.addTitle')}
        description={tr('members.addDesc')}
        onClose={onClose}
      />
      <form onSubmit={submit}>
        <DialogBody className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="add-email">{tr('members.email')}</Label>
            <Input
              id="add-email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="teammate@example.com"
              autoFocus
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="add-role">{tr('members.role')}</Label>
            <select
              id="add-role"
              value={role}
              onChange={(e) => setRole(e.target.value)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm shadow-sm"
            >
              <option value="member">{tr('members.roleMember')}</option>
              <option value="owner">{tr('members.roleOwner')}</option>
            </select>
          </div>
          {error ? <p className="text-xs text-destructive">{error}</p> : null}
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={submitting}>
            {tr('members.cancel')}
          </Button>
          <Button type="submit" disabled={submitting}>
            {submitting ? tr('members.adding') : tr('members.addMember')}
          </Button>
        </DialogFooter>
      </form>
    </Dialog>
  );
}
