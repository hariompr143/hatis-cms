import { NextResponse } from 'next/server';
import { clearedAccessTokenCookie } from '@/lib/session';

/** Clears the session cookie. The platform's refresh token is revoked separately. */
export async function POST() {
  const response = NextResponse.json({ ok: true });
  response.cookies.set(clearedAccessTokenCookie());
  return response;
}
