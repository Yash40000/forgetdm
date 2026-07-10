import type { NextConfig } from 'next';

const apiBase = process.env.FORGETDM_API_BASE || 'http://localhost:8088';

const nextConfig: NextConfig = {
  output: 'standalone',
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${apiBase}/api/:path*`
      }
    ];
  }
};

export default nextConfig;
