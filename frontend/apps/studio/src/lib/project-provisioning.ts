export interface ProjectProvisioningStatus {
  ref: string;
  initStatus: string | null;
  initMessage: string | null;
  enabled: boolean | null;
  running: boolean;
  startedAt: string | null;
  completedAt: string | null;
}

interface PollOptions {
  intervalMs?: number;
  timeoutMs?: number;
  signal?: AbortSignal;
  onStatus?: (status: ProjectProvisioningStatus) => void;
}

export class ProjectProvisioningFailedError extends Error {
  constructor(public readonly status: ProjectProvisioningStatus) {
    super(status.initMessage || 'Database provisioning failed.');
    this.name = 'ProjectProvisioningFailedError';
  }
}

export class ProjectProvisioningTimeoutError extends Error {
  constructor(public readonly lastStatus: ProjectProvisioningStatus) {
    super(
      'Provisioning is still running and exceeded the local monitoring window.',
    );
    this.name = 'ProjectProvisioningTimeoutError';
  }
}

export async function pollProjectProvisioning(
  loadStatus: () => Promise<ProjectProvisioningStatus>,
  options: PollOptions = {},
): Promise<ProjectProvisioningStatus> {
  const intervalMs = options.intervalMs ?? 1500;
  const timeoutMs = options.timeoutMs ?? 5 * 60 * 1000;
  const startedAt = Date.now();

  while (true) {
    throwIfAborted(options.signal);
    const status = await loadStatus();
    options.onStatus?.(status);

    const normalizedStatus = (status.initStatus ?? '').toUpperCase();
    if (normalizedStatus === 'INITIALIZED' && !status.running) {
      return status;
    }
    if (normalizedStatus === 'INIT_FAILED') {
      throw new ProjectProvisioningFailedError(status);
    }
    if (Date.now() - startedAt >= timeoutMs) {
      throw new ProjectProvisioningTimeoutError(status);
    }

    await wait(intervalMs, options.signal);
  }
}

function wait(durationMs: number, signal?: AbortSignal): Promise<void> {
  if (durationMs <= 0) {
    throwIfAborted(signal);
    return Promise.resolve();
  }

  return new Promise((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer);
      reject(abortReason(signal));
    };
    const timer = window.setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, durationMs);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

function throwIfAborted(signal?: AbortSignal) {
  if (signal?.aborted) {
    throw abortReason(signal);
  }
}

function abortReason(signal?: AbortSignal): Error {
  return signal?.reason instanceof Error
    ? signal.reason
    : new DOMException('Provisioning status polling aborted.', 'AbortError');
}
