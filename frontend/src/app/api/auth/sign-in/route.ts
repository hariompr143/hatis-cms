import { NextResponse } from 'next/server';
import { ApiError, request } from '@/lib/api';
import { accessTokenCookie } from '@/lib/session';

/**
 * Sign-in proxy.
 *
 * The browser posts credentials here; this handler forwards them to the platform
 * and stores the returned access token in an httpOnly cookie. The token never
 * reaches client-side JavaScript.
 */
export async function POST(httpRequest: Request) {
  let body: Record<string, unknown>;
  try {
    body = (await httpRequest.json()) as Record<string, unknown>;
  } catch {
    return NextResponse.json({ code: 'validation_failed', message: 'Invalid request body' }, { status: 400 });
  }

  try {
    const result = await request<SignInResult>('/v1/auth/sign-in', { method: 'POST', body });

    if (result.mfaRequired) {
      // No cookie is set until the second factor passes.
      return NextResponse.json({
        mfaRequired: true,
        challengeToken: result.challengeToken,
      });
    }

    const response = NextResponse.json({ mfaRequired: false });
    response.cookies.set(accessTokenCookie(result.accessToken, 15 * 60));
    return response;
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(
        { code: error.code, message: error.message, correlationId: error.correlationId },
        { status: error.status },
      );
    }
    return NextResponse.json(
      { code: 'dependency_unavailable', message: 'The platform did not respond' },
      { status: 503 },
    );
  }
}

interface SignInResult {
  mfaRequired?: boolean;
  challengeToken?: string;
  accessToken: string;
}
