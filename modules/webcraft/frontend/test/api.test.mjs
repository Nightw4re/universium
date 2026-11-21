import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer, request } from "node:http";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { createApiClient } from "../api.mjs";

const discovery = "/api/v1/modules";
const preview = discovery + "/demo/items/icon";
const equipment = discovery + "/demo/equipment/loadout";
const token = "V".repeat(43);

test("discovers over WebSocket and uses it for declared images while HTTP routes stay on HTTP", async () => {
  const calls = [];
  const socketCalls = [];
  class Socket extends EventTarget {
    static OPEN = 1;
    readyState = 0;

    constructor(url) {
      super();
      assert.equal(String(url), "wss://webcraft.example/api/v1/ws");
      queueMicrotask(() => {
        this.readyState = Socket.OPEN;
        this.dispatchEvent(new Event("open"));
      });
    }

    send(raw) {
      const message = JSON.parse(raw);
      socketCalls.push(message);
      const data = message.path === discovery
        ? { status: 200, body: { modules: [], websocketOnly: [preview, equipment] } }
        : message.path === equipment
          ? { status: 401, body: { error: "unauthorized" } }
          : { status: 200, contentType: "image/webp", base64: "AQID" };
      queueMicrotask(() => this.dispatchEvent(new MessageEvent("message", {
        data: JSON.stringify({ id: message.id, type: "response", response: data }),
      })));
    }
  }

  const { getJson, imageSource } = createApiClient({
    origin: () => "https://webcraft.example",
    WebSocket: Socket,
    fetch: async (path, options) => {
      calls.push([path, options]);
      return path === discovery
        ? new Response(null, { status: 405, headers: { "X-WebCraft-Transport": "websocket" } })
        : new Response('{"error":"not_found"}', { status: 404 });
    },
  });
  assert.deepEqual(await getJson(discovery), { modules: [], websocketOnly: [preview, equipment] });
  assert.equal(await imageSource(preview + "?id=minecraft%3Aapple"), "data:image/webp;base64,AQID");
  await assert.rejects(getJson(equipment, token), /unauthorized/);
  await assert.rejects(getJson("/api/v1/missing"), /not_found/);
  assert.deepEqual(calls.map(([path]) => path), [discovery, "/api/v1/missing"]);
  assert(calls.every(([, options]) => options.credentials === "omit"));
  assert.deepEqual(socketCalls.map(({ op, path, token }) => [op, path, token]), [
    ["get", discovery, undefined], ["get", preview + "?id=minecraft%3Aapple", undefined], ["get", equipment, token],
  ]);
});

function upgrade(port, path, headers) {
  return new Promise((resolve, reject) => {
    const client = request({ host: "127.0.0.1", port, path, headers, agent: false });
    client.on("response", response => {
      response.resume();
      resolve({ status: response.statusCode });
    });
    client.on("upgrade", (response, socket, head) => resolve({ status: response.statusCode, socket, head }));
    client.on("error", reject);
    client.setTimeout(3000, () => client.destroy(new Error("Upgrade timed out")));
    client.end();
  });
}

test("dev entry point forwards the public upgrade and blocks internal paths", { timeout: 8000 }, async () => {
  let forwarded;
  const upstream = createServer((incoming, outgoing) => {
    outgoing.writeHead(405, { "X-WebCraft-Transport": "websocket" }).end();
  });
  upstream.on("upgrade", (incoming, socket) => {
    forwarded = { path: incoming.url, headers: incoming.headers, socket };
    socket.write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n");
    socket.once("data", data => socket.write(data));
    socket.once("end", () => socket.end());
    socket.resume();
  });
  upstream.listen(0, "127.0.0.1");
  await once(upstream, "listening");

  const frontend = spawn(process.execPath, [fileURLToPath(new URL("../dev.mjs", import.meta.url))], {
    env: { ...process.env, WEBCRAFT_FRONTEND_ENABLED: "true", WEBCRAFT_DEV_PORT: "0",
      WEBCRAFT_API_PORT: String(upstream.address().port) },
    windowsHide: true,
    stdio: ["ignore", "pipe", "inherit"],
  });
  let downstream;
  try {
    const [output] = await once(frontend.stdout, "data", { signal: AbortSignal.timeout(4000) });
    const port = Number(/127\.0\.0\.1:(\d+)/.exec(String(output))[1]);
    const origin = "http://127.0.0.1:" + port;
    const headers = { Origin: origin, Connection: "Upgrade", Upgrade: "websocket",
      "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==" };
    const rejected = await upgrade(port, "/api/v1/internal/ws", headers);
    assert.equal(rejected.status, 404);
    assert.equal(forwarded, undefined);

    const connected = await upgrade(port, "/api/v1/ws", headers);
    downstream = connected.socket;
    assert.equal(connected.status, 101);
    assert.equal(forwarded.path, "/api/v1/ws");
    assert.equal(forwarded.headers.origin, origin);
    assert.equal(forwarded.headers.host, "127.0.0.1:" + port);
    const echoed = once(downstream, "data", { signal: AbortSignal.timeout(3000) });
    downstream.resume();
    downstream.write("test payload");
    assert.equal(String((await echoed)[0]), "test payload");
    const closed = once(forwarded.socket, "close", { signal: AbortSignal.timeout(3000) });
    downstream.destroy();
    await closed;

    assert.equal((await fetch(origin + "/api/v1/internal/players")).status, 404);
    const response = await fetch(origin + discovery);
    assert.equal(response.status, 405);
    assert.equal(response.headers.get("X-WebCraft-Transport"), "websocket");
  } finally {
    downstream?.destroy();
    forwarded?.socket.destroy();
    const exited = once(frontend, "exit");
    frontend.kill();
    await exited;
    await new Promise(resolve => upstream.close(resolve));
  }
});
