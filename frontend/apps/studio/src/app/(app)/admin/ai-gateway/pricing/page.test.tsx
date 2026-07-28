import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CustomerBillingPricesPage from './page';

const replace = vi.fn();

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace }) }));
vi.mock('@/lib/api', () => ({ apiFetch: vi.fn() }));
vi.mock('@/lib/session', () => ({
  useSession: () => ({
    platformKey: 'PLATFORM_KEY',
    user: { id: 'admin-id', email: 'admin@example.com', role: 'super_admin' },
    hasHydrated: true,
  }),
  isSuperAdmin: (user: { role?: string } | null) => user?.role === 'super_admin',
}));

import { apiFetch } from '@/lib/api';

const prices = [
  {
    id: 2,
    model: 'deepseek-v4-pro',
    normalizedModel: 'deepseek-v4-pro',
    provider: 'OPENAI',
    displayName: 'DeepSeek V4 Pro',
    currency: 'USD',
    inputPricePer1M: 1.25,
    outputPricePer1M: 5,
    cacheCreationPricePer1M: 0,
    cacheReadPricePer1M: 0.25,
    effectiveFrom: '2026-07-22T12:00:00Z',
    effectiveTo: null,
    active: true,
  },
];

const discovered = [
  {
    model: 'glm-5.2',
    normalizedModel: 'glm-5.2',
    provider: 'CLAUDE',
    channels: ['claude'],
    upstreams: ['glm-primary'],
    billingStatus: 'UNPRICED',
  },
];

describe('CustomerBillingPricesPage', () => {
  beforeEach(() => {
    replace.mockReset();
    vi.mocked(apiFetch).mockReset();
    vi.mocked(apiFetch).mockImplementation((path: string, options: any = {}) => {
      if (options.method === 'POST') return Promise.resolve(prices[0]);
      if (path.endsWith('/models/discovered')) return Promise.resolve(discovered);
      return Promise.resolve(prices);
    });
  });

  it('loads central price history and discovered platform models with platform auth', async () => {
    render(<CustomerBillingPricesPage />);

    expect(await screen.findByText('DeepSeek V4 Pro')).toBeInTheDocument();
    expect(screen.getByText('glm-5.2')).toBeInTheDocument();
    expect(apiFetch).toHaveBeenCalledWith(
      '/ai-gateway/platform/v1/billing/prices?activeOnly=false',
      { apikey: 'PLATFORM_KEY', authScope: 'platform' },
    );
    expect(apiFetch).toHaveBeenCalledWith(
      '/ai-gateway/platform/v1/billing/models/discovered',
      { apikey: 'PLATFORM_KEY', authScope: 'platform' },
    );
  });

  it('prefills an unpriced discovered model and publishes a new immutable price version', async () => {
    render(<CustomerBillingPricesPage />);
    await screen.findByText('DeepSeek V4 Pro');

    fireEvent.click(screen.getByRole('button', { name: 'glm-5.2' }));
    expect(screen.getByLabelText('Model')).toHaveValue('glm-5.2');
    expect(screen.getByLabelText('Provider')).toHaveValue('CLAUDE');

    fireEvent.change(screen.getByLabelText('Input'), { target: { value: '2.5' } });
    fireEvent.change(screen.getByLabelText('Output'), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Publish price version' }));

    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith(
        '/ai-gateway/platform/v1/billing/prices',
        expect.objectContaining({
          method: 'POST',
          apikey: 'PLATFORM_KEY',
          authScope: 'platform',
          body: expect.objectContaining({
            model: 'glm-5.2',
            provider: 'CLAUDE',
            currency: 'USD',
            inputPricePer1M: 2.5,
            outputPricePer1M: 10,
          }),
        }),
      );
    });
    expect(await screen.findByText(/Price published/)).toBeInTheDocument();
  });
});
