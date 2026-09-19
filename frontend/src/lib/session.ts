import { cookies } from 'next/headers';

/**
 * Session handling.
 *
 * The access token lives in an httpOnly cookie set by this server, never in
 * localStorage and never in a cookie readable by script. A console that renders
 * customer-authored content is exactly the place where an XSS would otherwise
 * become a session theft.
 */

const ACCESS_TOKEN_COOKIE = 'hatis_access_token';

export async function accessToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(ACCESS_TOKEN_COOKIE)?.value ?? null;
}

export function accessTokenCookie(value: string, maxAgeSeconds: number) {
  return {
    name: ACCESS_TOKEN_COOKIE,
    value,
    httpOnly: true,
    secure: true,
    sameSite: 'strict' as const,
    path: '/',
    maxAge: maxAgeSeconds,
  };
}

export function clearedAccessTokenCookie() {
  return {
    name: ACCESS_TOKEN_COOKIE,
    value: '',
    httpOnly: true,
    secure: true,
    sameSite: 'strict' as const,
    path: '/',
    maxAge: 0,
  };
}
