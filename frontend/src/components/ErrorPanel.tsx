import { ApiError } from '@/lib/api';
import { describeErrorCode } from '@/lib/format';

/**
 * Renders an API failure.
 *
 * Always shows the correlation id when the platform supplied one: it is the single
 * value that lets a support conversation be resolved against the logs.
 *
 * There are two shapes to handle, not one. A page that failed to render holds an
 * {@link ApiError} built by the server-side client. A client component that posted
 * to a route handler gets a plain response body back and rebuilds the error with
 * `Object.assign`, which is the shape this file's second branch reads. Recognising
 * it matters: without that branch every refused decision in the console reads
 * "Unexpected error" with the platform's code thrown away, which is exactly the
 * message a person cannot act on.
 */
interface CodedError {
  code: string;
  message?: unknown;
  correlationId?: unknown;
}

function asCodedError(error: unknown): CodedError | null {
  if (typeof error !== 'object' || error === null) {
    return null;
  }
  const candidate = error as { code?: unknown; message?: unknown; correlationId?: unknown };
  return typeof candidate.code === 'string' ? (candidate as CodedError) : null;
}

export function ErrorPanel({ error }: { error: unknown }) {
  if (error instanceof ApiError) {
    return (
      <div className="error-panel" role="alert">
        <strong>{describeErrorCode(error.code)}</strong>
        <div className="muted">{error.message}</div>
        {error.correlationId ? (
          <div>
            Reference: <code>{error.correlationId}</code>
          </div>
        ) : null}
      </div>
    );
  }

  const coded = asCodedError(error);
  if (coded) {
    const detail = typeof coded.message === 'string' && coded.message !== '' ? coded.message : String(error);
    return (
      <div className="error-panel" role="alert">
        <strong>{describeErrorCode(coded.code)}</strong>
        <div className="muted">{detail}</div>
        {typeof coded.correlationId === 'string' && coded.correlationId ? (
          <div>
            Reference: <code>{coded.correlationId}</code>
          </div>
        ) : null}
      </div>
    );
  }

  const message = error instanceof Error ? error.message : String(error);
  return (
    <div className="error-panel" role="alert">
      <strong>Unexpected error</strong>
      <div className="muted">{message}</div>
    </div>
  );
}
