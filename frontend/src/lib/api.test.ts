import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, query, request } from './api';

const originalFetch = globalThis.fetch;

/** The subset of Response the client actually reads. */
interface StubResponse {
  ok?: boolean;
  status?: number;
  statusText?: string;
  headers?: Record<string, string>;
  body?: string;
}

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

function stubFetch(stub: StubResponse) {
  const impl = vi.fn(async () => {
    const status = stub.status ?? 200;
    return {
      ok: stub.ok ?? (status >= 200 && status < 300),
      status,
      statusText: stub.statusText ?? 'OK',
      headers: new Headers(stub.headers ?? {}),
      text: async () => stub.body ?? '',
    } as unknown as Response;
  });
  globalThis.fetch = impl as unknown as typeof fetch;
  return impl;
}

/** Awaits a request that is expected to fail, and narrows the rejection. */
async function expectFailure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    return error as ApiError;
  }
  throw new Error('Expected the request to fail, but it succeeded');
}

describe('request', () => {
  it('sends the bearer token, correlation id and idempotency key', async () => {
    const fetchMock = stubFetch({ body: '{"ok":true}' });

    await request('/v1/deployments', {
      method: 'POST',
      body: { environmentId: 'env-1' },
      idempotencyKey: 'key-1',
      correlationId: 'corr-1',
      accessToken: 'token-1',
    });

    const call = fetchMock.mock.calls[0];
    expect(call).toBeDefined();
    const [, init] = call as unknown as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer token-1');
    expect(headers['Idempotency-Key']).toBe('key-1');
    expect(headers['X-Correlation-Id']).toBe('corr-1');
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"environmentId":"env-1"}');
  });

  it('never sends an Authorization header when no token is present', async () => {
    const fetchMock = stubFetch({ body: '{}' });

    await request('/v1/auth/sign-in', { method: 'POST', body: {} });

    const call = fetchMock.mock.calls[0];
    expect(call).toBeDefined();
    const [, init] = call as unknown as [string, RequestInit];
    expect(init.headers as Record<string, string>).not.toHaveProperty('Authorization');
  });

  it('surfaces the platform error code and correlation id on failure', async () => {
    stubFetch({
      ok: false,
      status: 409,
      body: JSON.stringify({
        code: 'quota_exceeded',
        message: 'Quota exceeded for content_items',
        correlationId: 'corr-9',
        details: { limitKey: 'content_items', limit: '2000' },
      }),
    });

    const error = await expectFailure(request('/v1/content/items', { method: 'POST' }));

    expect(error.status).toBe(409);
    expect(error.code).toBe('quota_exceeded');
    expect(error.message).toBe('Quota exceeded for content_items');
    expect(error.correlationId).toBe('corr-9');
    expect(error.details.limitKey).toBe('content_items');
    expect(error.isClientError).toBe(true);
  });

  it('falls back to the correlation id header when the body has none', async () => {
    stubFetch({
      ok: false,
      status: 503,
      headers: { 'X-Correlation-Id': 'from-header' },
    });

    const error = await expectFailure(request('/v1/assets'));

    expect(error.code).toBe('unknown_error');
    expect(error.correlationId).toBe('from-header');
    expect(error.isClientError).toBe(false);
  });

  it('does not throw on a non-JSON error body from a gateway', async () => {
    stubFetch({ ok: false, status: 502, statusText: 'Bad Gateway', body: '<html>502</html>' });

    const error = await expectFailure(request('/v1/assets'));

    expect(error.status).toBe(502);
    expect(error.message).toBe('Bad Gateway');
  });

  it('returns undefined for a 204 with no body', async () => {
    stubFetch({ status: 204 });

    await expect(request('/v1/assets/1', { method: 'DELETE' })).resolves.toBeUndefined();
  });
});

describe('query', () => {
  it('omits null, undefined and empty values', () => {
    expect(query({ projectId: 'p1', status: null, folderId: undefined, page: 0, blank: '' }))
      .toBe('?projectId=p1&page=0');
  });

  it('returns an empty string when there is nothing to send', () => {
    expect(query({})).toBe('');
  });

  it('encodes values that need it', () => {
    expect(query({ slug: 'a b/c' })).toBe('?slug=a+b%2Fc');
  });
});
