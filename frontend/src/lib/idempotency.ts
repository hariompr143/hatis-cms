/**
 * Idempotency keys for mutating actions.
 *
 * A user double-clicking "Deploy", or a form resubmitted after a network blip, must
 * not deploy twice. The key is derived from what the action *is*, not from when it
 * was attempted, so a genuine retry of the same intent collapses into one
 * execution while a different intent gets a different key.
 */

/**
 * Derives a stable key from the action and its inputs.
 *
 * Deliberately not random: a random key per click is exactly what fails to
 * deduplicate. The same deployment requested twice yields the same key, and the
 * platform returns the original response.
 */
export function idempotencyKey(action: string, inputs: Record<string, unknown>): string {
  const canonical = canonicalize({ action, ...inputs });
  return hash(canonical);
}

/**
 * Serialises a value deterministically.
 *
 * Object key order must not change the result, otherwise two callers describing the
 * same request would produce different keys and the platform would execute both.
 */
export function canonicalize(value: unknown): string {
  if (value === null || value === undefined) {
    return 'null';
  }
  if (typeof value === 'string') {
    return JSON.stringify(value);
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalize).join(',')}]`;
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, v]) => v !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      .map(([k, v]) => `${JSON.stringify(k)}:${canonicalize(v)}`);
    return `{${entries.join(',')}}`;
  }
  return 'null';
}

/**
 * FNV-1a over the canonical form, rendered as 16 hex characters.
 *
 * Not a cryptographic hash and not pretending to be: the platform is the authority
 * on whether a key was already used, and this only needs to be stable and
 * collision-resistant enough for form actions.
 */
export function hash(input: string): string {
  let high = 0x811c9dc5;
  let low = 0x01000193;
  for (let index = 0; index < input.length; index += 1) {
    const byte = input.charCodeAt(index) & 0xff;
    high ^= byte;
    low ^= byte;
    high = Math.imul(high, 0x01000193) >>> 0;
    low = Math.imul(low, 0x811c9dc5) >>> 0;
  }
  return `${high.toString(16).padStart(8, '0')}${low.toString(16).padStart(8, '0')}`;
}
