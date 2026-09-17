import Link from 'next/link';

export default function Home() {
  return (
    <main className="main" style={{ maxWidth: 640, margin: '80px auto' }}>
      <h1>HATIS</h1>
      <p className="muted">
        Multi-tenant content and deployment platform. The console is a client: every
        action it offers is re-checked on the server, so nothing here is a security
        control.
      </p>
      <p>
        <Link className="button" href="/signin">
          Sign in
        </Link>
      </p>
    </main>
  );
}
