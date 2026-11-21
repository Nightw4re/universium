import { motionPicture } from "./motion-picture.mjs";

const discovery = "/api/v1/modules";
const upgrade = "/api/v1/ws";
const timeout = 8000;

export function createApiClient({
  fetch: request = globalThis.fetch,
  WebSocket: Socket = globalThis.WebSocket,
  origin = () => location.origin,
} = {}) {
  let websocketOnly = new Set();
  let connection;
  let queue = Promise.resolve();
  let nextId = 0;

  function socketOnly(path) {
    return websocketOnly.has(path.split("?", 1)[0]);
  }

  function connect() {
    if (connection?.readyState === Socket.OPEN) return Promise.resolve(connection);
    const url = new URL(upgrade, origin());
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    const socket = new Socket(url);
    connection = socket;
    return wait(socket, "open").then(() => socket);
  }

  function wait(socket, event, send) {
    const listeners = new AbortController();
    return new Promise((resolve, reject) => {
      const limit = setTimeout(fail, timeout);
      const options = { once: true, signal: listeners.signal };
      socket.addEventListener("close", fail, options);
      socket.addEventListener("error", fail, options);
      socket.addEventListener(event, receive, options);
      try {
        send?.();
      } catch {
        fail();
      }

      function finish() {
        listeners.abort();
        clearTimeout(limit);
      }

      function fail() {
        finish();
        if (connection === socket) connection = undefined;
        socket.close();
        reject(new Error("api_unavailable"));
      }

      function receive(event) {
        finish();
        resolve(event);
      }
    });
  }

  function overSocket(path, token) {
    const result = queue.then(async () => {
      const socket = await connect();
      const id = String(++nextId);
      const command = { id, op: "get", path };
      if (token) command.token = token;
      const event = await wait(socket, "message", () => socket.send(JSON.stringify(command)));
      const message = JSON.parse(event.data);
      if (message.type !== "response" || message.id !== id || !Number.isInteger(message.response?.status)) {
        socket.close();
        throw new Error("invalid_api_response");
      }
      const response = message.response;
      if (response.status < 200 || response.status >= 300) {
        throw new Error(response.body?.error ?? "api_unavailable");
      }
      return response;
    });
    // A failed request must not prevent subsequent requests from opening a fresh connection.
    queue = result.then(() => {}, () => {});
    return result;
  }

  async function getJson(path, token) {
    let body;
    if (socketOnly(path)) {
      body = (await overSocket(path, token)).body;
    } else {
      const response = await request(path, {
        headers: token ? { Authorization: "Bearer " + token } : {},
        credentials: "omit",
        mode: "same-origin",
        cache: "no-store",
        redirect: "error",
        signal: AbortSignal.timeout(timeout),
      });
      if (response.status === 405 && response.headers.get("X-WebCraft-Transport") === "websocket") {
        await response.body?.cancel();
        body = (await overSocket(path, token)).body;
      } else {
        body = await response.json();
        if (!response.ok) throw new Error(body.error);
      }
    }
    if (path === discovery) websocketOnly = new Set(body.websocketOnly ?? []);
    return body;
  }

  async function imageSource(path) {
    if (!socketOnly(path)) return path;
    const { contentType, base64 } = await overSocket(path);
    if (!["image/png", "image/webp"].includes(contentType) || typeof base64 !== "string"
        || !/^[A-Za-z0-9+/]+={0,2}$/.test(base64)) {
      throw new Error("invalid_image_response");
    }
    return "data:" + contentType + ";base64," + base64;
  }

  return { getJson, imageSource, socketOnly };
}

const client = createApiClient();
export const { getJson, imageSource } = client;

const lazyLoads = new Map();
let observer;

function whenNear(image, load) {
  if (typeof IntersectionObserver === "undefined") {
    load();
    return;
  }
  if (!observer) {
    observer = new IntersectionObserver(entries => {
      for (const { target, isIntersecting } of entries) {
        if (!isIntersecting) continue;
        const load = lazyLoads.get(target);
        forget(target);
        if (target.isConnected) load?.();
      }
    }, { rootMargin: "240px" });
    new MutationObserver(records => {
      if (lazyLoads.size && records.some(record => record.removedNodes.length)) {
        for (const target of lazyLoads.keys()) {
          if (!target.isConnected) forget(target);
        }
      }
    }).observe(document.body, { childList: true, subtree: true });
  }
  lazyLoads.set(image, load);
  observer.observe(image);
}

function forget(image) {
  lazyLoads.delete(image);
  observer.unobserve(image);
}

export function apiImage(image, path, animatedPath) {
  const overSocket = client.socketOnly(path);
  const picture = animatedPath ? motionPicture(image, overSocket ? undefined : animatedPath) : image;
  if (!overSocket) {
    image.src = path;
    return picture;
  }

  function load() {
    imageSource(path).then(source => {
      image.src = source;
      if (animatedPath) {
        imageSource(animatedPath).then(source => {
          const animation = picture.querySelector("source");
          if (animation) animation.srcset = source;
        }).catch(() => {});
      }
    }).catch(() => image.dispatchEvent(new Event("error")));
  }
  if (image.loading === "lazy") whenNear(image, load);
  else load();
  return picture;
}
