import { describe, expect, it } from 'vitest';
import { canonicalize, hash, idempotencyKey } from './idempotency';

describe('canonicalize', () => {
  it('is independent of object key order', () => {
    expect(canonicalize({ a: 1, b: 2 })).toBe(canonicalize({ b: 2, a: 1 }));
  });

  it('distinguishes different values', () => {
    expect(canonicalize({ a: 1 })).not.toBe(canonicalize({ a: 2 }));
  });

  it('drops undefined values so an absent field matches an omitted one', () => {
    expect(canonicalize({ a: 1, b: undefined })).toBe(canonicalize({ a: 1 }));
  });

  it('keeps null, because null and absent are different requests', () => {
    expect(canonicalize({ a: null })).not.toBe(canonicalize({}));
  });

  it('handles nesting and arrays', () => {
    expect(canonicalize({ list: [1, 'two', { three: 3 }] }))
      .toBe('{"list":[1,"two",{"three":3}]}');
  });
});

describe('idempotencyKey', () => {
  it('is stable for the same action and inputs', () => {
    const first = idempotencyKey('deploy', { environmentId: 'e1', releaseId: 'r1' });
    const second = idempotencyKey('deploy', { releaseId: 'r1', environmentId: 'e1' });
    expect(first).toBe(second);
  });

  it('changes when an input changes', () => {
    const first = idempotencyKey('deploy', { environmentId: 'e1', releaseId: 'r1' });
    const second = idempotencyKey('deploy', { environmentId: 'e1', releaseId: 'r2' });
    expect(first).not.toBe(second);
  });

  it('changes when the action changes', () => {
    expect(idempotencyKey('deploy', { id: 'x' })).not.toBe(idempotencyKey('rollback', { id: 'x' }));
  });

  it('produces a 16 character hex key', () => {
    expect(idempotencyKey('deploy', { id: 'x' })).toMatch(/^[0-9a-f]{16}$/);
  });
});

describe('hash', () => {
  it('is deterministic and hex', () => {
    expect(hash('abc')).toBe(hash('abc'));
    expect(hash('abc')).toMatch(/^[0-9a-f]{16}$/);
  });

  it('does not collapse distinct inputs', () => {
    const seen = new Set(['a', 'b', 'ab', 'ba', 'deploy:e1', 'deploy:e2'].map(hash));
    expect(seen.size).toBe(6);
  });
});
