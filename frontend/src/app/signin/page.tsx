import { SignInForm } from './sign-in-form';

export const metadata = { title: 'Sign in — HATIS' };

export default function SignInPage() {
  return (
    <main className="main" style={{ maxWidth: 380, margin: '80px auto' }}>
      <h1>Sign in</h1>
      <p className="muted">
        Credentials are sent to the platform over TLS and are never stored by the
        console.
      </p>
      <SignInForm />
    </main>
  );
}
