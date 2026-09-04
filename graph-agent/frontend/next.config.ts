import type { NextConfig } from "next";

/**
 * Two builds come out of this file.
 *
 * `npm run dev` — the usual dev server on :3000, calling the API on :8080
 * through CORS. Nothing below applies.
 *
 * `npm run build` with BASE_PATH set — a static site with no Node server, for
 * the Python backend to serve from `backend/web/`. The whole UI is client
 * components already, so there is nothing to render on a server.
 *
 * BASE_PATH is the sub-path the platform mounts the app under (`/apps/<id>`).
 * Next writes it into every asset URL at build time, which is why changing the
 * app's id means rebuilding the frontend — an exported bundle cannot discover
 * its own mount point at runtime.
 */
const basePath = process.env.BASE_PATH ?? "";

const nextConfig: NextConfig = {
  ...(process.env.BUILD_STATIC === "1"
    ? {
        output: "export",
        // Emits `agents/<id>/index.html` rather than `agents/<id>.html`, which
        // is what lets a plain static file server resolve a directory URL.
        trailingSlash: true,
        basePath,
        assetPrefix: basePath,
        // The export has no server, so there is nothing to optimise images with.
        images: { unoptimized: true },
      }
    : {}),
};

export default nextConfig;
