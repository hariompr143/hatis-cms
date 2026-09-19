import { ApiError } from '@/lib/api';
import { describeErrorCode } from '@/lib/format';

/**
 * Renders an API failure.
 *
 * Always shows the correlation id when the platform supplied one: it is the single
 * value that lets a support conversation be resolved against the logs.
 */
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

  const message = error instanceof Error ? error.message : String(error);
  return (
    <div className="error-panel" role="alert">
      <strong>Unexpected error</strong>
      <div className="muted">{message}</div>
    </div>
  );
}
