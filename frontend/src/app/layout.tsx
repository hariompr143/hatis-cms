import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'HATIS',
  description: 'HATIS platform console',
  // The console manages tenants and deployments; it must never be framable.
  other: { 'X-Frame-Options': 'DENY' },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
