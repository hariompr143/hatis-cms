/**
 * The console is served from the platform's own origin in production, so the API is
 * reached through a relative path. Nothing in browser code may name a host: a
 * hard-coded URL breaks every preview environment and every private install.
 */
const nextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  async rewrites() {
    const target = process.env.HATIS_API_INTERNAL_URL ?? 'http://127.0.0.1:8080';
    return [{ source: '/api/:path*', destination: `${target}/:path*` }];
  },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'X-Frame-Options', value: 'DENY' },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Referrer-Policy', value: 'no-referrer' },
        ],
      },
    ];
  },
};

export default nextConfig;
