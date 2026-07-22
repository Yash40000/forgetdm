import type { NextConfig } from 'next';

const apiBase = process.env.FORGETDM_API_BASE || 'http://localhost:8088';
const distDir = process.env.FORGETDM_NEXT_DIST_DIR?.trim();

const nextConfig: NextConfig = {
  output: 'standalone',
  ...(distDir ? { distDir } : {}),
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
