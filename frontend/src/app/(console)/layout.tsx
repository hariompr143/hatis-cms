import Link from 'next/link';
import { redirect } from 'next/navigation';
import { accessToken } from '@/lib/session';

const NAV = [
  { href: '/projects', label: 'Projects' },
  { href: '/content', label: 'Content' },
  { href: '/assets', label: 'Assets' },
  { href: '/deployments', label: 'Deployments' },
  { href: '/domains', label: 'Domains' },
];

/**
 * Console shell.
 *
 * Without a session cookie there is nothing to render, so the shell redirects
 * rather than showing empty pages. This is a UX decision, not an access control:
 * the platform rejects an unauthenticated API call regardless of what this renders.
 */
export default async function ConsoleLayout({ children }: { children: React.ReactNode }) {
  const token = await accessToken();
  if (!token) {
    redirect('/signin');
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">HATIS</div>
        <nav>
          {NAV.map((item) => (
            <Link key={item.href} href={item.href}>
              {item.label}
            </Link>
          ))}
        </nav>
      </aside>
      <main className="main">{children}</main>
    </div>
  );
}
