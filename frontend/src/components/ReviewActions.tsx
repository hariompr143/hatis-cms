'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { ErrorPanel } from '@/components/ErrorPanel';

/**
 * The review actions available for one content item.
 *
 * What is offered is decided by the item's status, and that is a usability decision
 * rather than a security one: the platform refuses anything the item's state does not
 * allow, and the console is not a place to re-implement that check. Offering an
 * action the server would refuse is what produces the "why is this button here"
 * support ticket, so the status decides.
 *
 * Each action posts to the console's own route handler. The browser never holds the
 * access token and never names the platform's host, which is what keeps a preview
 * environment, a private install and production on the same build.
 */
interface ReviewAction {
  name: 'submit' | 'approve' | 'reject';
  label: string;
  /** Whether the decision carries a comment. */
  comment: boolean;
  /** A destructive or negative decision is styled as such. */
  tone: 'primary' | 'danger' | 'plain';
}

/** Actions each status offers, read against the seeded editorial definition. */
const ACTIONS_BY_STATUS: Record<string, ReviewAction[]> = {
  DRAFT: [
    { name: 'submit', label: 'Submit for review', comment: false, tone: 'primary' },
  ],
  APPROVED: [
    { name: 'submit', label: 'Submit for review', comment: false, tone: 'primary' },
  ],
  IN_REVIEW: [
    { name: 'approve', label: 'Approve', comment: true, tone: 'primary' },
    { name: 'reject', label: 'Reject', comment: true, tone: 'danger' },
  ],
};

export function reviewActionsFor(status: string): ReviewAction[] {
  return ACTIONS_BY_STATUS[status.toUpperCase()] ?? [];
}

export function ReviewActions({ itemId, status }: { itemId: string; status: string }) {
  const router = useRouter();
  const actions = reviewActionsFor(status);
  const [comment, setComment] = useState('');
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  if (actions.length === 0) {
    return <span className="muted">—</span>;
  }

  async function decide(action: ReviewAction) {
    setPending(action.name);
    setError(null);
    try {
      const response = await fetch(`/api/content/${encodeURIComponent(itemId)}/review`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: action.name, comment }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        setError(
          Object.assign(new Error(body.message ?? 'The decision was not accepted'), {
            code: body.code,
            status: response.status,
            correlationId: body.correlationId,
          }),
        );
        return;
      }
      setComment('');
      // Re-reads the page's server data: the item's status and the row's actions both
      // changed, and they changed on the server rather than in this component.
      router.refresh();
    } catch (cause) {
      setError(cause);
    } finally {
      setPending(null);
    }
  }

  return (
    <div className="review-actions">
      {actions.some((action) => action.comment) ? (
        <input
          type="text"
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          maxLength={2000}
          placeholder="Comment (optional)"
          aria-label="Review comment"
        />
      ) : null}
      {actions.map((action) => (
        <button
          key={action.name}
          type="button"
          className={action.tone === 'plain' ? undefined : action.tone}
          disabled={pending !== null}
          onClick={() => decide(action)}
        >
          {pending === action.name ? `${action.label}…` : action.label}
        </button>
      ))}
      {error ? <ErrorPanel error={error} /> : null}
    </div>
  );
}
