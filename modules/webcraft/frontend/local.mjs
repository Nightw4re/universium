import { createServer, request } from "node:http";
import { readFile } from "node:fs/promises";

const enabled = process.env.WEBCRAFT_FRONTEND_ENABLED ?? "false";
if (enabled === "false") {
  console.log("WebCraft preview frontend is disabled.");
  process.exit(0);
}
if (enabled !== "true") {
  console.error("WEBCRAFT_FRONTEND_ENABLED must be true or false.");
  process.exit(1);
}

const webPort = Number(process.env.WEBCRAFT_DEV_PORT || 4173);
const apiPort = Number(process.env.WEBCRAFT_API_PORT || 8765);
const files = new Map([
  ["/", ["index.html", "text/html"]],
  ["/index.html", ["index.html", "text/html"]],
  ["/style.css", ["style.css", "text/css"]],
  ["/app.mjs", ["app.mjs", "text/javascript"]],
  ["/catalog.mjs", ["catalog.mjs", "text/javascript"]],
  ["/glint.mjs", ["glint.mjs", "text/javascript"]],
  ["/preview.mjs", ["preview.mjs", "text/javascript"]],
  ["/motion-picture.mjs", ["motion-picture.mjs", "text/javascript"]],
  ["/designs.mjs", ["designs.mjs", "text/javascript"]],
  ["/design-preview.mjs", ["design-preview.mjs", "text/javascript"]],
  ["/banners.mjs", ["banners.mjs", "text/javascript"]],
  ["/armor.mjs", ["armor.mjs", "text/javascript"]],
  ["/armor-geometry.mjs", ["armor-geometry.mjs", "text/javascript"]],
  ["/armor-look.mjs", ["armor-look.mjs", "text/javascript"]],
  ["/armor-skin.mjs", ["armor-skin.mjs", "text/javascript"]],
]);

createServer(async (incoming, outgoing) => {
  const path = incoming.url;
  const method = incoming.method;
  if (path.startsWith("/api/v1/") && method === "GET") {
    const authorization = incoming.headers.authorization;
    const upstream = request({
      hostname: "127.0.0.1", port: apiPort, path, method,
      headers: authorization ? { Authorization: authorization } : {},
    }, response => {
      outgoing.writeHead(response.statusCode, response.headers);
      response.pipe(outgoing);
    });
    upstream.setTimeout(5000, () => upstream.destroy());
    upstream.on("error", () => {
      if (outgoing.headersSent) {
        outgoing.destroy();
      } else {
        outgoing.writeHead(502, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        outgoing.end('{"error":"api_unavailable"}');
      }
    });
    outgoing.on("close", () => upstream.destroy());
    upstream.end();
    return;
  }
  const file = method === "GET" && files.get(path);
  if (!file) {
    outgoing.writeHead(404).end();
    return;
  }
  try {
    const content = await readFile(new URL(file[0], import.meta.url));
    outgoing.writeHead(200, {
      "Content-Type": file[1] + "; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    }).end(content);
  } catch {
    outgoing.writeHead(500).end();
  }
}).listen(webPort, "127.0.0.1", () => console.log("WebCraft at http://127.0.0.1:" + webPort));
