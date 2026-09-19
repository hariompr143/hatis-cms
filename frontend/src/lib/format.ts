/**
 * Presentation helpers.
 *
 * Pure functions, so they are tested directly rather than through a rendered
 * component. Nothing here talks to the API or holds state.
 */

/** Human-readable bytes, using binary units because storage quotas are binary. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) {
    return '—';
  }
  if (bytes < 0) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let value = bytes;
  let unit = -1;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const rounded = value >= 100 ? Math.round(value) : Number(value.toFixed(1));
  return `${rounded} ${units[unit]}`;
}

/** Relative time, falling back to an absolute date beyond a week. */
export function formatRelativeTime(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) {
    return '—';
  }
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) {
    return '—';
  }
  const seconds = Math.round((now.getTime() - then.getTime()) / 1000);
  if (seconds < 0) {
    return 'in the future';
  }
  if (seconds < 45) {
    return 'just now';
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return `${minutes} minute${minutes === 1 ? '' : 's'} ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  }
  const days = Math.round(hours / 24);
  if (days < 7) {
    return `${days} day${days === 1 ? '' : 's'} ago`;
  }
  return then.toISOString().slice(0, 10);
}

/**
 * Tone for a status badge.
 *
 * Kept as an explicit map rather than a colour guess so a new status is a
 * compile-time problem instead of an unstyled badge in production.
 */
export type StatusTone = 'positive' | 'warning' | 'negative' | 'neutral';

const STATUS_TONES: Record<string, StatusTone> = {
  ACTIVE: 'positive',
  READY: 'positive',
  PUBLISHED: 'positive',
  SUCCESS: 'positive',
  COMPLETED: 'positive',
  HEALTHY: 'positive',
  CLEAN: 'positive',
  PASSED: 'positive',
  VERIFIED: 'positive',
  PENDING: 'neutral',
  PENDING_VERIFICATION: 'neutral',
  CREATED: 'neutral',
  DRAFT: 'neutral',
  UNKNOWN: 'neutral',
  PROVISIONING: 'warning',
  RUNNING: 'warning',
  IN_REVIEW: 'warning',
  APPROVED: 'warning',
  RENEWING: 'warning',
  SKIPPED: 'warning',
  DEGRADED: 'warning',
  PAST_DUE: 'warning',
  FAILED: 'negative',
  ERROR: 'negative',
  INFECTED: 'negative',
  QUARANTINED: 'negative',
  REJECTED: 'negative',
  CANCELLED: 'negative',
  UNHEALTHY: 'negative',
  EXPIRED: 'negative',
  SUSPENDED: 'negative',
  DELETED: 'negative',
  RETIRED: 'negative',
  ARCHIVED: 'neutral',
};

export function statusTone(status: string | null | undefined): StatusTone {
  if (!status) {
    return 'neutral';
  }
  return STATUS_TONES[status.toUpperCase()] ?? 'neutral';
}

/** Renders an API error code as something a person can act on. */
export function describeErrorCode(code: string): string {
  switch (code) {
    case 'unauthenticated':
      return 'Your session has expired. Sign in again.';
    case 'forbidden':
      return 'You do not have permission to do that.';
    case 'tenant_mismatch':
      return 'That resource belongs to another workspace.';
    case 'quota_exceeded':
      return 'Your plan limit for this resource has been reached.';
    case 'entitlement_missing':
      return 'Your plan does not include this capability.';
    case 'idempotency_conflict':
      return 'This action was already submitted with different details. Reload and try again.';
    case 'validation_failed':
      return 'Some of the values you entered are not valid.';
    case 'state_conflict':
      return 'That resource is not in a state that allows this action.';
    case 'dependency_unavailable':
      return 'A required service is temporarily unavailable. Try again shortly.';
    default:
      return 'Something went wrong. The reference id is in the details if you need support.';
  }
}
