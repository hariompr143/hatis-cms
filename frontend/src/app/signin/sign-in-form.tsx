'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { ErrorPanel } from '@/components/ErrorPanel';

/**
 * Sign-in form.
 *
 * Posts to a route handler on this origin rather than calling the platform API
 * directly from the browser. The access token is stored in an httpOnly cookie by
 * that handler, so no script running in the console can read it — an XSS in a
 * rendered content preview must not become a session theft.
 */
export function SignInForm() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [challengeToken, setChallengeToken] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      const response = await fetch('/api/auth/sign-in', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(
          challengeToken
            ? { challengeToken, code: mfaCode }
            : { email, password },
        ),
      });
      const body = await response.json();
      if (!response.ok) {
        setError(Object.assign(new Error(body.message ?? 'Sign-in failed'), { code: body.code }));
        return;
      }
      if (body.mfaRequired) {
        setChallengeToken(body.challengeToken);
        return;
      }
      router.push('/projects');
      router.refresh();
    } catch (cause) {
      setError(cause);
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={submit} className="card">
      {error ? <ErrorPanel error={error} /> : null}

      {challengeToken ? (
        <label>
          Authentication code
          <input
            value={mfaCode}
            onChange={(event) => setMfaCode(event.target.value)}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            required
          />
        </label>
      ) : (
        <>
          <label>
            Email
            <input
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="username"
              required
            />
          </label>
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
        </>
      )}

      <button type="submit" disabled={pending}>
        {pending ? 'Working…' : challengeToken ? 'Verify' : 'Sign in'}
      </button>
    </form>
  );
}
