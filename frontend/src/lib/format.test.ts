import { describe, expect, it } from 'vitest';
import { describeErrorCode, formatBytes, formatRelativeTime, statusTone } from './format';

describe('formatBytes', () => {
  it('uses binary units', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1024)).toBe('1 KiB');
    expect(formatBytes(1536)).toBe('1.5 KiB');
    expect(formatBytes(1048576)).toBe('1 MiB');
    expect(formatBytes(5 * 1024 * 1024 * 1024)).toBe('5 GiB');
  });

  it('shows no decimal once the value is large', () => {
    expect(formatBytes(150 * 1024 * 1024)).toBe('150 MiB');
  });

  it('returns a placeholder for missing or negative values', () => {
    expect(formatBytes(null)).toBe('—');
    expect(formatBytes(undefined)).toBe('—');
    expect(formatBytes(-1)).toBe('—');
  });
});

describe('formatRelativeTime', () => {
  const now = new Date('2026-09-16T12:00:00.000Z');

  it('describes recent times relatively', () => {
    expect(formatRelativeTime('2026-09-16T11:59:40.000Z', now)).toBe('just now');
    expect(formatRelativeTime('2026-09-16T11:30:00.000Z', now)).toBe('30 minutes ago');
    expect(formatRelativeTime('2026-09-16T09:00:00.000Z', now)).toBe('3 hours ago');
    expect(formatRelativeTime('2026-09-14T12:00:00.000Z', now)).toBe('2 days ago');
  });

  it('falls back to an absolute date beyond a week', () => {
    expect(formatRelativeTime('2026-08-01T12:00:00.000Z', now)).toBe('2026-08-01');
  });

  it('uses singular forms', () => {
    expect(formatRelativeTime('2026-09-16T11:00:00.000Z', now)).toBe('1 hour ago');
    expect(formatRelativeTime('2026-09-15T12:00:00.000Z', now)).toBe('1 day ago');
  });

  it('handles missing and invalid input', () => {
    expect(formatRelativeTime(null, now)).toBe('—');
    expect(formatRelativeTime('not-a-date', now)).toBe('—');
    expect(formatRelativeTime('2026-09-17T12:00:00.000Z', now)).toBe('in the future');
  });
});

describe('statusTone', () => {
  it('maps known statuses', () => {
    expect(statusTone('ACTIVE')).toBe('positive');
    expect(statusTone('PUBLISHED')).toBe('positive');
    expect(statusTone('DEGRADED')).toBe('warning');
    expect(statusTone('INFECTED')).toBe('negative');
    expect(statusTone('QUARANTINED')).toBe('negative');
  });

  it('is case insensitive and defaults to neutral', () => {
    expect(statusTone('active')).toBe('positive');
    expect(statusTone('SOMETHING_NEW')).toBe('neutral');
    expect(statusTone(null)).toBe('neutral');
  });
});

describe('describeErrorCode', () => {
  it('explains the codes a user can act on', () => {
    expect(describeErrorCode('unauthenticated')).toContain('Sign in again');
    expect(describeErrorCode('quota_exceeded')).toContain('plan limit');
    expect(describeErrorCode('tenant_mismatch')).toContain('another workspace');
  });

  it('has a fallback for unknown codes', () => {
    expect(describeErrorCode('something_new')).toContain('went wrong');
  });
});
