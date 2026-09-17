import { statusTone } from '@/lib/format';

/**
 * Renders a lifecycle status.
 *
 * The tone comes from an explicit map, so an unrecognised status renders as neutral
 * rather than silently looking healthy.
 */
export function StatusBadge({ status }: { status: string | null | undefined }) {
  if (!status) {
    return <span className="badge">—</span>;
  }
  return <span className={`badge ${statusTone(status)}`}>{status.replace(/_/g, ' ').toLowerCase()}</span>;
}
