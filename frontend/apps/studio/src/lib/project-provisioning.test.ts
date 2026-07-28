import { describe, expect, it, vi } from 'vitest';
import {
  pollProjectProvisioning,
  ProjectProvisioningFailedError,
  ProjectProvisioningTimeoutError,
  type ProjectProvisioningStatus,
} from './project-provisioning';

describe('pollProjectProvisioning', () => {
  it('polls until the persisted status is initialized and the worker has finished', async () => {
    const loadStatus = vi
      .fn<() => Promise<ProjectProvisioningStatus>>()
      .mockResolvedValueOnce(status('INITIALIZING', true))
      .mockResolvedValueOnce(status('INITIALIZED', true))
      .mockResolvedValueOnce(status('INITIALIZED', false));

    const result = await pollProjectProvisioning(loadStatus, {
      intervalMs: 0,
      timeoutMs: 1000,
    });

    expect(result.initStatus).toBe('INITIALIZED');
    expect(loadStatus).toHaveBeenCalledTimes(3);
  });

  it('surfaces the persisted initialization failure message', async () => {
    const failed = status('INIT_FAILED', false, 'permission denied');

    await expect(
      pollProjectProvisioning(() => Promise.resolve(failed), {
        intervalMs: 0,
        timeoutMs: 1000,
      }),
    ).rejects.toEqual(
      expect.objectContaining<ProjectProvisioningFailedError>({
        message: 'permission denied',
        status: failed,
      }),
    );
  });

  it('reports monitoring timeout without treating it as a database failure', async () => {
    const initializing = status('INITIALIZING', true);

    await expect(
      pollProjectProvisioning(() => Promise.resolve(initializing), {
        intervalMs: 0,
        timeoutMs: 0,
      }),
    ).rejects.toBeInstanceOf(ProjectProvisioningTimeoutError);
  });
});

function status(
  initStatus: string,
  running: boolean,
  initMessage: string | null = null,
): ProjectProvisioningStatus {
  return {
    ref: 'demo',
    initStatus,
    initMessage,
    enabled: initStatus === 'INITIALIZED',
    running,
    startedAt: null,
    completedAt: null,
  };
}
