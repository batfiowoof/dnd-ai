import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  // The /api rewrite below proxies to the backend, whose World Builder AI generations are slow on a
  // free model (a quest batch can take ~190s, occasionally longer). Next's rewrite proxy otherwise
  // resets idle upstream connections after a hard-coded 30s (ECONNRESET "socket hang up"), cutting the
  // request just as the backend is still waiting on the model. Raise it to match the backend's
  // spring.http.client.read-timeout (360s) so both hops tolerate the same worst case.
  experimental: {
    proxyTimeout: 360_000,
  },
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination:
          process.env.BACKEND_URL
            ? `${process.env.BACKEND_URL}/api/:path*`
            : "http://localhost:8080/api/:path*",
      },
    ];
  },
};

export default nextConfig;
