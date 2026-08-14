import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotProvisioned } from './not-provisioned';
import { I18nProvider } from '../lib/i18n';

const routerRefresh = vi.fn();
const setProject = vi.fn();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ refresh: routerRefresh }),
}));
vi.mock('../lib/route-params', () => ({
  useProjectRef: (projectRef: string) => projectRef,
}));
vi.mock('../lib/session', () => ({
  useSession: () => ({
    platformKey: 'PLATFORM_KEY',
    project: { ref: 'demo', apikey: '', initStatus: 'PENDING_INIT' },
    setProject,
  }),
}));
vi.mock('../lib/api', () => ({
  apiFetch: vi.fn(),
  fetchAllProjects: vi.fn(),
}));
vi.mock('../lib/project-provisioning', async () => {
  const actual = await vi.importActual<
    typeof import('../lib/project-provisioning')
  >('../lib/project-provisioning');
  return {
    ...actual,
    pollProjectProvisioning: vi.fn(),
  };
});

import { apiFetch, fetchAllProjects } from '../lib/api';
import {
  pollProjectProvisioning,
  ProjectProvisioningTimeoutError,
  type ProjectProvisioningStatus,
} from '../lib/project-provisioning';

describe('NotProvisioned', () => {
  beforeEach(() => {
    routerRefresh.mockReset();
    setProject.mockReset();
    vi.mocked(apiFetch).mockReset();
    vi.mocked(fetchAllProjects).mockReset();
    vi.mocked(pollProjectProvisioning).mockReset();
    vi.mocked(apiFetch).mockResolvedValue({});
  });

  it('submits once, waits for terminal status, then refreshes the project context', async () => {
    const initialized = status('INITIALIZED', false);
    vi.mocked(pollProjectProvisioning).mockImplementation(
      async (loadStatus, options) => {
        const current = await loadStatus();
        options?.onStatus?.(current);
        return current;
      },
    );
    vi.mocked(apiFetch).mockImplementation(
      (path: string, options: any = {}) => {
        if (options.method === 'POST')
          return Promise.resolve({ submissionState: 'QUEUED' });
        if (path.endsWith('/provision')) return Promise.resolve(initialized);
        return Promise.resolve({});
      },
    );
    vi.mocked(fetchAllProjects).mockResolvedValue([
      {
        ref: 'demo',
        name: 'Demo',
        apikey: 'SERVICE_ROLE_KEY',
        initStatus: 'INITIALIZED',
        healthStatus: 'HEALTHY',
      },
    ]);

    render(<I18nProvider><NotProvisioned projectRef="demo" initStatus="PENDING_INIT" /></I18nProvider>);

    await waitFor(() => {
      expect(setProject).toHaveBeenCalledWith(
        expect.objectContaining({
          ref: 'demo',
          initStatus: 'INITIALIZED',
        }),
      );
    });
    expect(apiFetch).toHaveBeenCalledWith(
      '/auth/v1/admin/projects/demo/provision',
      expect.objectContaining({ method: 'POST', authScope: 'platform' }),
    );
    expect(routerRefresh).toHaveBeenCalled();
  });

  it('describes a monitoring timeout without claiming the database failed', async () => {
    const initializing = status('INITIALIZING', true);
    vi.mocked(pollProjectProvisioning).mockRejectedValue(
      new ProjectProvisioningTimeoutError(initializing),
    );

    render(<I18nProvider><NotProvisioned projectRef="demo" initStatus="PENDING_INIT" /></I18nProvider>);

    expect(
      await screen.findByText(
        'Database provisioning is taking longer than expected',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Check status/ }),
    ).toBeInTheDocument();
    expect(
      screen.queryByText('Database provisioning failed'),
    ).not.toBeInTheDocument();
  });
});

function status(
  initStatus: string,
  running: boolean,
): ProjectProvisioningStatus {
  return {
    ref: 'demo',
    initStatus,
    initMessage: null,
    enabled: initStatus === 'INITIALIZED',
    running,
    startedAt: null,
    completedAt: null,
  };
}
