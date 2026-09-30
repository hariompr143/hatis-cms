import { NextResponse } from 'next/server';
import { ApiError, request } from '@/lib/api';
import { idempotencyKey } from '@/lib/idempotency';
import { accessToken } from '@/lib/session';

/**
 * Review decisions for one content item.
 *
 * The browser posts an intent — submit, approve, reject — and this handler turns it
 * into the platform call. Two things are deliberate:
 *
 *   * the access token never leaves the server, so the browser cannot call the
 *     platform directly and cannot be tricked into calling it with a different item
 *     id than the one this page rendered;
 *   * the action is validated against a closed set here. The handler is a thin
 *     proxy, but "thin" is not the same as "passes anything through": an unknown
 *     action would otherwise become a request path built from user input.
 *
 * Authorization is not checked here and must not be: the platform decides, and a
 * console that pre-judged the answer would be a second, weaker implementation of
 * the rule. A denied decision comes back as `forbidden` with the correlation id of
 * the request that was actually refused.
 */
const ACTIONS = {
  submit: { path: 'submit', body: false, label: 'submit for review' },
  approve: { path: 'approve', body: true, label: 'approve' },
  reject: { path: 'reject', body: true, label: 'reject' },
} as const;

type ActionName = keyof typeof ACTIONS;

function isAction(value: unknown): value is ActionName {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(ACTIONS, value);
}

export async function POST(
  httpRequest: Request,
  context: { params: Promise<{ itemId: string }> },
) {
  const { itemId } = await context.params;

  let payload: { action?: unknown; comment?: unknown };
  try {
    payload = (await httpRequest.json()) as { action?: unknown; comment?: unknown };
  } catch {
    return NextResponse.json({ code: 'validation_failed', message: 'Invalid request body' }, { status: 400 });
  }

  if (!isAction(payload.action)) {
    return NextResponse.json(
      { code: 'validation_failed', message: 'Unknown review action' },
      { status: 400 },
    );
  }
  const action = ACTIONS[payload.action];
  const comment = typeof payload.comment === 'string' && payload.comment.trim() !== ''
    ? payload.comment.trim()
    : undefined;

  const token = await accessToken();
  if (!token) {
    return NextResponse.json(
      { code: 'unauthenticated', message: 'Your session has expired. Sign in again.' },
      { status: 401 },
    );
  }

  try {
    await request(`/v1/content/items/${encodeURIComponent(itemId)}/${action.path}`, {
      method: 'POST',
      accessToken: token ?? undefined,
      body: action.body ? { comment } : undefined,
      // Derived from the decision rather than generated per click: a double click on
      // "approve" is one decision, and the platform should see one request. The
      // comment is part of the identity of the decision — the same reviewer approving
      // with different notes is a different intent.
      idempotencyKey: idempotencyKey(`content.${payload.action}`, { itemId, comment }),
    });
    return NextResponse.json({ ok: true });
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(
        { code: error.code, message: error.message, correlationId: error.correlationId },
        { status: error.status },
      );
    }
    return NextResponse.json(
      { code: 'dependency_unavailable', message: 'The platform did not respond' },
      { status: 503 },
    );
  }
}
