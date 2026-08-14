'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { CloudOff, Loader2, Zap } from 'lucide-react';
import { Button, Card, CardContent } from '@nubase/ui';
import { apiFetch, fetchAllProjects, type ApiError } from '@/lib/api';
import {
  pollProjectProvisioning,
  ProjectProvisioningFailedError,
  ProjectProvisioningTimeoutError,
  type ProjectProvisioningStatus,
} from '@/lib/project-provisioning';
import { useSession, type ProjectContext } from '@/lib/session';
import { useProjectRef } from '@/lib/route-params';
import { useI18n } from '@/lib/i18n';

interface NotProvisionedProps {
  projectRef: string;
  initStatus?: string | null;
}

type ProvisioningFailureKind = 'database' | 'monitoring' | 'request';

interface ProvisioningFailure {
  kind: ProvisioningFailureKind;
  message: string;
}

/**
 * Shown on data pages while the project's database isn't initialised yet. The user
 * shouldn't have to click anything: provisioning starts automatically on mount and
 * this just reports progress, falling back to a manual retry if it fails.
 */
export function NotProvisioned({
  projectRef,
  initStatus,
}: NotProvisionedProps) {
  const { tr } = useI18n();
  const router = useRouter();
  const { platformKey, project, setProject } = useSession();
  const resolvedProjectRef = useProjectRef(projectRef);
  const [running, setRunning] = useState(false);
  const [observedStatus, setObservedStatus] = useState(initStatus ?? null);
  const [failure, setFailure] = useState<ProvisioningFailure | null>(null);
  const controllerRef = useRef<AbortController | null>(null);
  const runIdRef = useRef(0);

  async function provision() {
    if (!platformKey || !resolvedProjectRef) return;
    const runId = ++runIdRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setRunning(true);
    setFailure(null);
    try {
      await apiFetch(
        `/auth/v1/admin/projects/${encodeURIComponent(resolvedProjectRef)}/provision`,
        {
          method: 'POST',
          apikey: platformKey,
          authScope: 'platform',
          signal: controller.signal,
        },
      );
      await pollProjectProvisioning(
        () =>
          apiFetch<ProjectProvisioningStatus>(
            `/auth/v1/admin/projects/${encodeURIComponent(resolvedProjectRef)}/provision`,
            {
              apikey: platformKey,
              authScope: 'platform',
              signal: controller.signal,
            },
          ),
        {
          signal: controller.signal,
          onStatus: (status) => {
            if (runId === runIdRef.current) {
              setObservedStatus(status.initStatus);
            }
          },
        },
      );
      if (runId !== runIdRef.current) return;
      const refreshed = await fetchProject(platformKey, resolvedProjectRef);
      if (refreshed) {
        setProject(refreshed);
      } else if (project && project.ref === resolvedProjectRef) {
        setProject({ ...project, initStatus: 'INITIALIZED' });
      }
      router.refresh();
    } catch (err) {
      if (controller.signal.aborted || runId !== runIdRef.current) return;
      if (err instanceof ProjectProvisioningFailedError) {
        setObservedStatus(err.status.initStatus);
        setFailure({ kind: 'database', message: err.status.initMessage ?? tr('notProvisioned.titleDB') });
      } else if (err instanceof ProjectProvisioningTimeoutError) {
        setObservedStatus(err.lastStatus.initStatus);
        setFailure({ kind: 'monitoring', message: tr('notProvisioned.monitorTimeout') });
      } else {
        setFailure({
          kind: 'request',
          message:
            (err as ApiError).message ??
            tr('notProvisioned.startFailed'),
        });
      }
    } finally {
      if (runId === runIdRef.current) {
        setRunning(false);
      }
    }
  }

  // Strict Mode mounts this effect twice in development. Aborting the previous run and
  // relying on backend per-project deduplication keeps both the browser and worker safe.
  useEffect(() => {
    if (!platformKey || !resolvedProjectRef) return;
    void provision();
    return () => {
      controllerRef.current?.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [platformKey, resolvedProjectRef]);

  const failed = !!failure && !running;
  const currentStatus = observedStatus ?? initStatus ?? 'unknown';
  const failureTitle =
    failure?.kind === 'database'
      ? tr('notProvisioned.titleDB')
      : failure?.kind === 'monitoring'
        ? tr('notProvisioned.titleSlow')
        : tr('notProvisioned.titleMonitor');
  const failureDescription =
    failure?.kind === 'database'
      ? tr('notProvisioned.descDB')
      : tr('notProvisioned.descMonitor');

  return (
    <div className="p-8">
      <Card>
        <CardContent className="flex flex-col items-center gap-3 py-16 text-center">
          {failed ? (
            <>
              <CloudOff className="h-8 w-8 text-muted-foreground" />
              <h2 className="text-lg font-semibold">{failureTitle}</h2>
              <p className="max-w-md text-sm text-muted-foreground">
                {tr('notProvisioned.state', { status: currentStatus })}
                {' '}
                {failureDescription}
              </p>
              <p className="max-w-md text-xs text-destructive">
                {failure?.message}
              </p>
              <div className="flex gap-2 pt-2">
                <Button size="sm" onClick={provision} disabled={running}>
                  <Zap className="h-3.5 w-3.5" />
                  {failure?.kind === 'database' ? tr('notProvisioned.retry') : tr('notProvisioned.checkStatus')}
                </Button>
                <Link href={`/project/${resolvedProjectRef}/settings`}>
                  <Button variant="outline" size="sm">
                    {tr('notProvisioned.openSettings')}
                  </Button>
                </Link>
              </div>
            </>
          ) : (
            <>
              <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
              <h2 className="text-lg font-semibold">{tr('notProvisioned.initializing')}</h2>
              <p className="max-w-md text-sm text-muted-foreground">
                {tr('notProvisioned.initializingDesc', { ref: resolvedProjectRef })}
              </p>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

interface ProjectSummary {
  ref: string;
  name?: string | null;
  initStatus?: string | null;
  healthStatus?: string | null;
  apikey?: string | null;
}

async function fetchProject(
  platformKey: string,
  projectRef: string,
): Promise<ProjectContext | null> {
  const projects = await fetchAllProjects<ProjectSummary>(platformKey);
  const project = projects.find((p) => p.ref === projectRef);
  if (!project) return null;
  return {
    ref: project.ref,
    apikey: project.apikey ?? '',
    name: project.name ?? project.ref,
    initStatus: project.initStatus ?? null,
    healthStatus: project.healthStatus ?? null,
  };
}
