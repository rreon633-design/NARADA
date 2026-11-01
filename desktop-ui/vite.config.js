import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";
import path from "node:path";
const here = path.dirname(fileURLToPath(import.meta.url));
const token = process.env.NARADA_DEV_TOKEN;
export default defineConfig({
  clearScreen: false,
  server: {
    host: "0.0.0.0",
    port: Number(process.env.NARADA_UI_PORT || 5173),
    strictPort: true,
    allowedHosts: ["localhost", "127.0.0.1", ".e2b.app"],
    fs: {
      strict: true,
      allow: [here, path.join(here, "../node_modules")],
      deny: [
        ".env",
        "**/.env.*",
        "**/.narada-data/**",
        "**/.cache/development-vaults/**",
      ],
    },
    proxy: token
      ? {
          "/__narada": {
            target: `http://127.0.0.1:${process.env.NARADA_PREVIEW_PORT || 4319}`,
            rewrite: (p) => p.replace(/^\/__narada/, ""),
            configure(proxy) {
              proxy.on("proxyReq", (p) =>
                p.setHeader("x-narada-dev-token", token),
              );
            },
          },
        }
      : undefined,
  },
  plugins: [
    {
      name: "narada-preview-origin-guard",
      configureServer(server) {
        server.middlewares.use("/__narada", (req, res, next) => {
          const origin = req.headers.origin;
          let badOrigin = false;
          try {
            if (origin) badOrigin = new URL(origin).host !== req.headers.host;
          } catch {
            badOrigin = true;
          }
          if (badOrigin || req.headers["sec-fetch-site"] === "cross-site") {
            res.statusCode = 403;
            res.setHeader("Content-Type", "application/json");
            res.end(
              JSON.stringify({
                error: {
                  code: "ORIGIN_REJECTED",
                  message:
                    "Cross-origin development commands are not permitted.",
                },
              }),
            );
            return;
          }
          next();
        });
      },
    },
  ],
  build: { target: "es2022", sourcemap: false },
});
