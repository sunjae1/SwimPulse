import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  allowedDevOrigins: ["unnamable-preset-contact.ngrok-free.dev"],
  async rewrites() {
    return [
      {
        source: "/oauth2/:path*",
        destination: "http://localhost:8080/oauth2/:path*",
      },
      {
        source: "/login/oauth2/:path*",
        destination: "http://localhost:8080/login/oauth2/:path*",
      },
      {
        source: "/api/:path*",
        destination: "http://localhost:8080/api/:path*",
      },
    ];
  },
};

export default nextConfig;
