import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReviewActions, reviewActionsFor } from './ReviewActions';

/**
 * The review actions a console offers.
 *
 * Two properties matter here. The actions follow the item's status, because offering
 * one the platform would refuse is a support ticket rather than a feature. And a
 * refused decision is shown as what the platform said, with its reference id — a
 * reviewer who is told "something went wrong" cannot tell a permission problem from a
 * race with another reviewer.
 */

vi.mock('next/navigation', () => ({
  useRouter: () => ({ refresh: vi.fn() }),
}));

const originalFetch = global.fetch;

afterEach(() => {
  global.fetch = originalFetch;
  vi.restoreAllMocks();
});

describe('reviewActionsFor', () => {
  it('offers submission for a draft and for an approved item', () => {
    expect(reviewActionsFor('DRAFT').map((action) => action.name)).toEqual(['submit']);
    expect(reviewActionsFor('APPROVED').map((action) => action.name)).toEqual(['submit']);
  });

  it('offers a decision only while an item is in review', () => {
    expect(reviewActionsFor('IN_REVIEW').map((action) => action.name)).toEqual(['approve', 'reject']);
  });

  it('offers nothing for a status the editorial flow does not move from', () => {
    expect(reviewActionsFor('PUBLISHED')).toEqual([]);
    expect(reviewActionsFor('ARCHIVED')).toEqual([]);
    expect(reviewActionsFor('DELETED')).toEqual([]);
  });

  it('is case-insensitive, so a lower-cased status still offers its actions', () => {
    expect(reviewActionsFor('in_review').map((action) => action.name)).toEqual(['approve', 'reject']);
  });
});

describe('ReviewActions', () => {
  it('posts the decision to the console route handler, not to the platform', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ok: true }) });
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<ReviewActions itemId="item-1" status="IN_REVIEW" />);
    fireEvent.change(screen.getByLabelText('Review comment'), { target: { value: 'Looks good' } });
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe('/api/content/item-1/review');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({ action: 'approve', comment: 'Looks good' });
  });

  it('submits a draft without asking for a comment', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ok: true }) });
    global.fetch = fetchMock as unknown as typeof fetch;

    render(<ReviewActions itemId="item-2" status="DRAFT" />);

    expect(screen.queryByLabelText('Review comment')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Submit for review' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/content/item-2/review');
  });

  it('shows what the platform said when a decision is refused', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({
        code: 'forbidden',
        message: "'approve' is assigned to ORG_ADMIN",
        correlationId: 'corr-789',
      }),
    }) as unknown as typeof fetch;

    render(<ReviewActions itemId="item-3" status="IN_REVIEW" />);
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));

    expect(await screen.findByText(/do not have permission/)).toBeInTheDocument();
    expect(screen.getByText("'approve' is assigned to ORG_ADMIN")).toBeInTheDocument();
    expect(screen.getByText('corr-789')).toBeInTheDocument();
  });

  it('renders nothing to do for a status with no available action', () => {
    render(<ReviewActions itemId="item-4" status="PUBLISHED" />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
