import { createServer, request, STATUS_CODES } from "node:http";
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
  ["/api.mjs", ["api.mjs", "text/javascript"]],
  ["/catalog.mjs", ["catalog.mjs", "text/javascript"]],
  ["/item-feeds.mjs", ["item-feeds.mjs", "text/javascript"]],
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

const server = createServer(async (incoming, outgoing) => {
  const path = incoming.url;
  const method = incoming.method;
  if (path.startsWith("/api/v1/") && !/^\/api\/v1\/internal(?:[/?]|$)/.test(path) && method === "GET") {
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
});

function endUpgrade(socket, status) {
  socket.end(`HTTP/1.1 ${status} ${STATUS_CODES[status]}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
}

server.on("upgrade", (incoming, downstream, head) => {
  downstream.on("error", () => downstream.destroy());
  if (incoming.method !== "GET" || incoming.url !== "/api/v1/ws") {
    endUpgrade(downstream, 404);
    return;
  }
  if (incoming.headersDistinct.host?.length !== 1) {
    endUpgrade(downstream, 400);
    return;
  }
  const headers = {};
  for (const name of ["host", "origin", "upgrade", "connection", "authorization",
    "sec-websocket-key", "sec-websocket-version"]) {
    const values = incoming.headersDistinct[name];
    if (values) headers[name] = values.length === 1 ? values[0] : values;
  }
  downstream.pause();
  let tunnel;
  const upstream = request({
    hostname: "127.0.0.1", port: apiPort, path: incoming.url, method: incoming.method, headers, agent: false,
  });
  upstream.setTimeout(5000, () => upstream.destroy());
  upstream.on("upgrade", (response, socket, upstreamHead) => {
    tunnel = socket;
    socket.setTimeout(0);
    socket.on("error", () => downstream.destroy());
    socket.on("close", () => downstream.destroy());
    downstream.write("HTTP/1.1 101 Switching Protocols\r\n");
    for (let index = 0; index < response.rawHeaders.length; index += 2) {
      downstream.write(response.rawHeaders[index] + ": " + response.rawHeaders[index + 1] + "\r\n");
    }
    downstream.write("\r\n");
    if (upstreamHead.length) downstream.write(upstreamHead);
    if (head.length) socket.write(head);
    downstream.pipe(socket).pipe(downstream);
  });
  upstream.on("response", response => {
    endUpgrade(downstream, response.statusCode);
    response.resume();
  });
  upstream.on("error", () => {
    if (!downstream.destroyed) endUpgrade(downstream, 502);
  });
  downstream.on("close", () => {
    if (tunnel) tunnel.destroy();
    else upstream.destroy();
  });
  upstream.end();
});

server.listen(webPort, "127.0.0.1", () => console.log("WebCraft at http://127.0.0.1:" + server.address().port));
