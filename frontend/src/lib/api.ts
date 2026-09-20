/**
 * API client.
 *
 * Runs on the server (in route handlers and server components). Browser code never
 * imports this and never names a host: the console reaches the platform through a
 * relative path, so a preview environment, a private install and production all
 * work without a rebuild.
 *
 * The client deliberately does not retry mutating requests on its own. A retry
 * without an idempotency key can deploy twice; retries belong to the caller, which
 * supplies the key.
 */

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId: string | null;
  readonly details: Record<string, string>;

  constructor(status: number, code: string, message: string,
              correlationId: string | null, details: Record<string, string>) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
    this.details = details;
  }

  /** True when the platform refused the action rather than failing to perform it. */
  get isClientError(): boolean {
    return this.status >= 400 && this.status < 500;
  }
}

interface ApiErrorBody {
  code?: string;
  message?: string;
  correlationId?: string | null;
  details?: Record<string, string>;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Required for anything that must not be executed twice. */
  idempotencyKey?: string;
  /** Propagated so a page render and its API calls share one trace. */
  correlationId?: string;
  accessToken?: string;
  signal?: AbortSignal;
}

const DEFAULT_TIMEOUT_MS = 15_000;

function baseUrl(): string {
  // Server-to-server. Never exposed to the browser.
  const internal = process.env.HATIS_API_INTERNAL_URL;
  return internal ?? 'http://127.0.0.1:8080';
}

/**
 * Performs a request and returns the decoded body.
 *
 * A non-2xx response is always an {@link ApiError} carrying the platform's error
 * code and correlation id, so a caller can show a useful message and support can
 * find the request in the logs.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (options.idempotencyKey) {
    headers['Idempotency-Key'] = options.idempotencyKey;
  }
  if (options.correlationId) {
    headers['X-Correlation-Id'] = options.correlationId;
  }
  if (options.accessToken) {
    headers.Authorization = `Bearer ${options.accessToken}`;
  }

  const response = await fetch(`${baseUrl()}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal ?? AbortSignal.timeout(DEFAULT_TIMEOUT_MS),
    cache: 'no-store',
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const parsed = text ? safeParse(text) : null;

  if (!response.ok) {
    const body = (parsed ?? {}) as ApiErrorBody;
    throw new ApiError(
      response.status,
      body.code ?? 'unknown_error',
      body.message ?? response.statusText,
      body.correlationId ?? response.headers.get('X-Correlation-Id'),
      body.details ?? {},
    );
  }

  return (parsed ?? undefined) as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    // A gateway that returns HTML on a 502 must not turn into a JSON parse error;
    // the status code is the real information.
    return null;
  }
}

/** Builds a query string, skipping null and undefined so callers stay readable. */
export function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') {
      search.set(key, String(value));
    }
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : '';
}
