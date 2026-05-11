package com.universium.webcraft.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.universium.webcraft.config.webcraftConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.BindException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class webcraftHttpServer {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static FileChannel startupLockChannel;
    private static FileLock startupLock;
    private final webcraftConfig config;
    private final webcraftRouteDispatcher dispatcher;
    private final webcraftResourcePackRegistry resourcePackRegistry;
    private final webcraftPreviewCache previewCache;
    private final webcraftLogWriter logWriter;
    private final ExecutorService executor;
    private final Path metadataPath = Path.of("config").resolve("webcraft.metadata");
    private int boundPort = -1;
    private HttpServer server;
    private webcraftInstanceMetadata metadata;

    public webcraftHttpServer(webcraftConfig config, webcraftRouteDispatcher dispatcher) {
        this(config, dispatcher, new webcraftResourcePackRegistry(config), new webcraftLogWriter(
                config.logging().accessEnabled(),
                config.logging().errorEnabled(),
                Path.of("config").resolve(config.logging().accessFile()),
                Path.of("config").resolve(config.logging().errorFile())
        ));
    }

    public webcraftHttpServer(webcraftConfig config, webcraftRouteDispatcher dispatcher, webcraftResourcePackRegistry resourcePackRegistry) {
        this(config, dispatcher, resourcePackRegistry, new webcraftLogWriter(
                config.logging().accessEnabled(),
                config.logging().errorEnabled(),
                Path.of("config").resolve(config.logging().accessFile()),
                Path.of("config").resolve(config.logging().errorFile())
        ));
    }

    public webcraftHttpServer(webcraftConfig config, webcraftRouteDispatcher dispatcher, webcraftResourcePackRegistry resourcePackRegistry, webcraftLogWriter logWriter) {
        this.config = Objects.requireNonNull(config, "config");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.resourcePackRegistry = Objects.requireNonNull(resourcePackRegistry, "resourcePackRegistry");
        this.previewCache = new webcraftPreviewCache(resourcePackRegistry, Path.of("config").resolve("webcraft-cache").resolve("previews"));
        this.logWriter = Objects.requireNonNull(logWriter, "logWriter");
        this.executor = Executors.newCachedThreadPool();
    }

    public synchronized void start() throws IOException {
        if (server != null || !STARTED.compareAndSet(false, true)) {
            return;
        }
        long bootstrapStartedAt = System.nanoTime();
        if (!acquireStartupLock()) {
            STARTED.set(false);
            return;
        }
        metadata = webcraftInstanceMetadata.current(config.server().host(), config.server().port(), config.server().useHttps(), System.currentTimeMillis());
        int port = resolvePort();
        InetSocketAddress address = new InetSocketAddress(config.server().host(), port);
        try {
            server = createServer(address);
        } catch (BindException exception) {
            if (shouldReuseOwnInstance()) {
                killOwnInstance();
                server = createServer(address);
            } else if (config.server().autoPort()) {
                int fallbackPort = freePort(config.server().host());
                server = createServer(new InetSocketAddress(config.server().host(), fallbackPort));
                boundPort = fallbackPort;
            } else {
                STARTED.set(false);
                releaseStartupLock();
                throw exception;
            }
        }
        if (boundPort < 0) {
            boundPort = server.getAddress().getPort();
        }
        metadata = new webcraftInstanceMetadata(metadata.instanceId(), ProcessHandle.current().pid(), config.server().host(), boundPort, config.server().useHttps(), metadata.startedAtMillis());
        writeMetadata();
        dispatcher.setRuntimePort(boundPort);
        server.createContext("/resources/index.json", new ResourceIndexHandler());
        server.createContext("/resources/models/index.json", new ResourceModelsHandler());
        server.createContext("/resources/previews", new ResourcePreviewHandler());
        server.createContext("/resources", new ResourceTextureHandler());
        server.createContext("/api/resources/index.json", new ResourceIndexHandler());
        server.createContext("/api/resources/models/index.json", new ResourceModelsHandler());
        server.createContext("/api/resources/previews", new ResourcePreviewHandler());
        server.createContext("/api/resources", new ResourceTextureHandler());
        server.createContext("/api/resourcepacks", new ResourcePackHandler());
        server.createContext("/api", new DispatchHandler());
        server.createContext("/", new StaticWebrootHandler());
        server.setExecutor(executor);
        server.start();
        String scheme = config.server().useHttps() ? "https" : "http";
        long apiReadyAt = System.nanoTime();
        logWriter.info("webcraft HTTP server started on " + scheme + "://" + config.server().host() + ":" + boundPort);
        long bootstrapMs = Math.max(0L, (apiReadyAt - bootstrapStartedAt) / 1_000_000L);
        System.out.println("[WebCraft] API ready in " + bootstrapMs + "ms: " + scheme + "://" + config.server().host() + ":" + boundPort + "/api/");
        System.out.println("[WebCraft] Frontend ready in " + bootstrapMs + "ms: " + scheme + "://" + config.server().host() + ":" + boundPort + "/");
        if (config.server().generateResourcesAtStart()) {
            long warmupStartedAt = System.nanoTime();
            Runnable warmupTask = () -> {
                try {
                    previewCache.warm(config.server().resourcesWarmupBatchSize(), config.server().resourcesWarmupPauseMs());
                    long warmupMs = Math.max(0L, (System.nanoTime() - warmupStartedAt) / 1_000_000L);
                    logWriter.info("webcraft resources warmup completed");
                    System.out.println("[WebCraft] Resources warmup completed in " + warmupMs + "ms");
                } catch (Exception exception) {
                    logWriter.error("webcraft resources warmup", exception);
                }
            };
            if (config.server().resourcesWarmupAsync()) {
                Thread warmupThread = new Thread(warmupTask, "webcraft-resource-warmup");
                warmupThread.setDaemon(true);
                warmupThread.setPriority(Thread.MIN_PRIORITY);
                warmupThread.start();
            } else {
                warmupTask.run();
            }
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            boundPort = -1;
        }
        releaseStartupLock();
        deleteMetadata();
        STARTED.set(false);
        executor.shutdownNow();
    }

    public synchronized int boundPort() {
        return boundPort;
    }

    private final class DispatchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            webcraftRequest request = toRequest(exchange);
            webcraftResponse response;
            try {
                response = dispatcher.dispatch(request);
                logWriter.access(request.method() + " " + request.path() + " -> " + response.status());
            } catch (Exception exception) {
                logWriter.error(request.method() + " " + request.path(), exception);
                response = new webcraftResponse(500, "application/json", "{\"success\":false,\"error\":\"internal_server_error\"}");
            }

            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }

    private final class StaticWebrootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendText(exchange, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}", "application/json");
                    return;
                }

                String requestPath = exchange.getRequestURI().getPath();
                if (requestPath == null || requestPath.isBlank() || "/".equals(requestPath)) {
                    sendFile(exchange, resolveWebrootFile(config.server().webrootIndex()), "text/html; charset=UTF-8");
                    return;
                }
                if (requestPath.startsWith("/api")) {
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }

                Path root = resolveWebrootRoot();
                Path candidate = root.resolve(requestPath.substring(1)).normalize();
                if (!candidate.startsWith(root) || !Files.exists(candidate) || Files.isDirectory(candidate)) {
                    if (requestPath.endsWith("/")) {
                        Path indexCandidate = root.resolve(requestPath.substring(1)).resolve(config.server().webrootIndex()).normalize();
                        if (indexCandidate.startsWith(root) && Files.exists(indexCandidate)) {
                            sendFile(exchange, indexCandidate, contentType(indexCandidate));
                            return;
                        }
                    }
                    Path indexCandidate = resolveWebrootFile(config.server().webrootIndex());
                    if (Files.exists(indexCandidate)) {
                        sendFile(exchange, indexCandidate, "text/html; charset=UTF-8");
                        return;
                    }
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }

                sendFile(exchange, candidate, contentType(candidate));
            } catch (Exception exception) {
                logWriter.error("GET " + exchange.getRequestURI().getPath(), exception);
                sendText(exchange, 500, "{\"success\":false,\"error\":\"internal_server_error\"}", "application/json");
            }
        }
    }

    private final class ResourcePackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendText(exchange, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}", "application/json");
                    return;
                }

                String requestPath = exchange.getRequestURI().getPath();
                if (requestPath != null && requestPath.startsWith("/api/")) {
                    requestPath = requestPath.substring("/api".length());
                }
                if (requestPath == null || requestPath.isBlank() || "/resourcepacks".equals(requestPath) || "/resourcepacks.json".equals(requestPath)) {
                    setResourceCacheHeaders(exchange, resourceEtag(), config.server().resourcesCacheMaxAgeSeconds());
                    sendText(exchange, 200, resourcePackRegistry.toManifestJson(), "application/json");
                    return;
                }

                if (!requestPath.startsWith("/resourcepacks/")) {
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }

                String relative = requestPath.substring("/resourcepacks/".length());
                int slash = relative.indexOf('/');
                if (slash <= 0) {
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }

                String packId = relative.substring(0, slash);
                String remainder = relative.substring(slash + 1);
                webcraftResourcePack pack = resourcePackRegistry.loadPacks().stream()
                        .filter(candidate -> candidate.id().equals(packId))
                        .findFirst()
                        .orElse(null);
                if (pack == null) {
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }

                Path root = resolveResourcePackRoot().resolve(pack.path()).normalize();
                Path candidate = root.resolve(remainder).normalize();
                if (!candidate.startsWith(root) || !Files.exists(candidate) || Files.isDirectory(candidate)) {
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }

                setResourceCacheHeaders(exchange, resourceEtag(), config.server().resourcesCacheMaxAgeSeconds());
                sendFile(exchange, candidate, contentType(candidate));
            } catch (Exception exception) {
                logWriter.error("GET " + exchange.getRequestURI().getPath(), exception);
                sendText(exchange, 500, "{\"success\":false,\"error\":\"internal_server_error\"}", "application/json");
            }
        }
    }

    private final class FaviconHandler implements HttpHandler {
        private final byte[] transparentPng = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO6qX4kAAAAASUVORK5CYII="
        );

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendText(exchange, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}", "application/json");
                    return;
                }
                sendBytes(exchange, transparentPng, "image/png");
            } catch (Exception exception) {
                logWriter.error("GET /favicon.ico", exception);
                sendText(exchange, 500, "{\"success\":false,\"error\":\"internal_server_error\"}", "application/json");
            }
        }
    }

    private final class ResourceTextureHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendText(exchange, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}", "application/json");
                    return;
                }

                String requestPath = exchange.getRequestURI().getPath();
                if (requestPath != null && requestPath.startsWith("/api/")) {
                    requestPath = requestPath.substring("/api".length());
                }
                if (requestPath == null || !requestPath.startsWith("/resources/textures/")) {
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }

                String relative = requestPath.substring("/resources/textures/".length());
                if (relative != null && relative.endsWith("air.png")) {
                    setResourceCacheHeaders(exchange, resourceEtag(), config.server().resourcesCacheMaxAgeSeconds());
                    sendBytes(exchange, Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAO6qX4kAAAAASUVORK5CYII="), "image/png");
                    return;
                }
                byte[] resolved = resourcePackRegistry.resolveTexture(relative);
                if (resolved == null || resolved.length == 0) {
                    logWriter.error("resource texture miss: " + relative, new IOException("not found"));
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }

                setResourceCacheHeaders(exchange, resourceEtag(), config.server().resourcesCacheMaxAgeSeconds());
                sendBytes(exchange, resolved, contentType(relative));
            } catch (Exception exception) {
                logWriter.error("GET " + exchange.getRequestURI().getPath(), exception);
                sendText(exchange, 500, "{\"success\":false,\"error\":\"internal_server_error\"}", "application/json");
            }
        }
    }

    private final class ResourcePreviewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendText(exchange, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}", "application/json");
                    return;
                }
                String requestPath = exchange.getRequestURI().getPath();
                if (requestPath != null && requestPath.startsWith("/api/")) {
                    requestPath = requestPath.substring("/api".length());
                }
                if (requestPath == null || !requestPath.startsWith("/resources/previews/")) {
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }
                String relative = requestPath.substring("/resources/previews/".length());
                byte[] resolved = previewCache.resolvePreview(relative);
                if (resolved == null || resolved.length == 0) {
                    sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
                    return;
                }
                setResourceCacheHeaders(exchange, resourceEtag(), config.server().resourcesCacheMaxAgeSeconds());
                sendBytes(exchange, resolved, "image/png");
            } catch (Exception exception) {
                logWriter.error("GET " + exchange.getRequestURI().getPath(), exception);
                sendText(exchange, 500, "{\"success\":false,\"error\":\"internal_server_error\"}", "application/json");
            }
        }
    }

    private final class ResourceIndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendText(exchange, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}", "application/json");
                    return;
                }
                Path artifactsDir = Path.of("modules", "webcraft", "build", "moddev", "artifacts");
                long artifactCount = 0L;
                if (Files.exists(artifactsDir)) {
                    try (var stream = Files.list(artifactsDir)) {
                        artifactCount = stream.count();
                    }
                }
                String userDir = System.getProperty("user.dir", "");
                String json = "{"
                        + "\"success\":true,"
                        + "\"data\":{"
                        + "\"userDir\":" + quoteJson(userDir) + ","
                        + "\"artifactsDirExists\":" + Files.exists(artifactsDir) + ","
                        + "\"artifactCount\":" + artifactCount + ","
                        + "\"packCount\":" + resourcePackRegistry.loadPacks().size() + ","
                        + "\"textureCount\":" + resourcePackRegistry.textureIndex().size() + ","
                        + "\"hasDiamondSword\":" + (resourcePackRegistry.resolveTexture("item/diamond_sword.png") != null)
                        + "}"
                        + "}";
                sendText(exchange, 200, json, "application/json");
            } catch (Exception exception) {
                logWriter.error("GET " + exchange.getRequestURI().getPath(), exception);
                sendText(exchange, 500, "{\"success\":false,\"error\":\"internal_server_error\"}", "application/json");
            }
        }
    }

    private final class ResourceModelsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendText(exchange, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}", "application/json");
                    return;
                }
                setResourceCacheHeaders(exchange, resourceEtag(), config.server().resourcesCacheMaxAgeSeconds());
                sendText(exchange, 200, resourcePackRegistry.modelsJson(), "application/json");
            } catch (Exception exception) {
                logWriter.error("GET " + exchange.getRequestURI().getPath(), exception);
                sendText(exchange, 500, "{\"success\":false,\"error\":\"internal_server_error\"}", "application/json");
            }
        }
    }

    private void sendFile(HttpExchange exchange, Path path, String contentType) throws IOException {
        if (!Files.exists(path)) {
            sendText(exchange, 404, "{\"success\":false,\"error\":\"not_found\"}", "application/json");
            return;
        }
        byte[] bytes = Files.readAllBytes(path);
        setNoCacheHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        } else {
            exchange.getResponseBody().close();
        }
    }

    private void sendBytes(HttpExchange exchange, byte[] bytes, String contentType) throws IOException {
        setNoCacheHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        } else {
            exchange.getResponseBody().close();
        }
    }

    private void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        setNoCacheHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        } else {
            exchange.getResponseBody().close();
        }
    }

    private void setNoCacheHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
    }

    private void setResourceCacheHeaders(HttpExchange exchange, String etag, int maxAgeSeconds) {
        String safeEtag = etag == null || etag.isBlank() ? "" : etag;
        exchange.getResponseHeaders().set("ETag", safeEtag);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=" + Math.max(0, maxAgeSeconds));
    }

    private static String contentType(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (fileName.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (fileName.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (fileName.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        if (fileName.endsWith(".txt")) {
            return "text/plain; charset=UTF-8";
        }
        return "application/octet-stream";
    }

    private static String contentType(String path) {
        String fileName = path == null ? "" : path.toLowerCase();
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    private static String quoteJson(String value) {
        String safe = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safe + "\"";
    }

    private String resourceEtag() {
        if (metadata == null) {
            return "\"webcraft-resource-unknown\"";
        }
        return "\"webcraft-resource-" + metadata.instanceId() + "-" + metadata.startedAtMillis() + "\"";
    }

    private Path resolveWebrootRoot() {
        Path configured = Path.of(config.server().webrootDir());
        Path[] candidates = new Path[] {
                configured,
                Path.of("..").resolve(configured),
                Path.of("..", "..").resolve(configured),
                Path.of("modules", "webcraft", "webroot"),
                Path.of("webroot"),
                Path.of("src", "main", "resources", "webroot")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return configured.toAbsolutePath().normalize();
    }

    private Path resolveResourcePackRoot() {
        Path configured = Path.of(config.server().resourcePacksDir());
        Path[] candidates = new Path[] {
                configured,
                Path.of("..").resolve(configured),
                Path.of("..", "..").resolve(configured),
                Path.of("resourcepacks"),
                Path.of("modules", "webcraft", "resourcepacks")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return configured.toAbsolutePath().normalize();
    }

    private Path resolveWebrootFile(String relativePath) throws IOException {
        Path root = resolveWebrootRoot();
        Path candidate = root.resolve(relativePath).normalize();
        if (candidate.startsWith(root) && Files.exists(candidate)) {
            return candidate;
        }
        Path fallback = Path.of(config.server().webrootDir()).resolve(relativePath).normalize();
        if (Files.exists(fallback)) {
            return fallback.toAbsolutePath().normalize();
        }
        throw new IOException("Missing webroot file: " + relativePath);
    }

    private webcraftRequest toRequest(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getPath();
        Map<String, String> queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
        return new webcraftRequest(method, rawPath, queryParams, Map.of());
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            String key = index >= 0 ? pair.substring(0, index) : pair;
            String value = index >= 0 ? pair.substring(index + 1) : "";
            result.put(urlDecode(key), urlDecode(value));
        }
        return result;
    }

    private static String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return value;
        }
    }

    private int resolvePort() {
        int desiredPort = config.server().port();
        if (isPortAvailable(config.server().host(), desiredPort)) {
            return desiredPort;
        }
        if (shouldReuseOwnInstance() && metadata != null) {
            return metadata.port();
        }
        return config.server().autoPort() ? freePort(config.server().host()) : desiredPort;
    }

    private static boolean isPortAvailable(String host, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(host, port));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static int freePort(String host) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(host, 0));
            return socket.getLocalPort();
        } catch (IOException exception) {
            return 0;
        }
    }

    private boolean shouldReuseOwnInstance() {
        webcraftInstanceMetadata existing = webcraftInstanceMetadata.read(metadataPath);
        return existing != null && existing.matches(config.server().host(), config.server().port(), config.server().useHttps()) && processAlive(existing.processId());
    }

    private void killOwnInstance() {
        webcraftInstanceMetadata existing = webcraftInstanceMetadata.read(metadataPath);
        if (existing == null) {
            return;
        }
        ProcessHandle.of(existing.processId()).ifPresent(processHandle -> {
            if (processHandle.isAlive()) {
                processHandle.destroy();
                if (processHandle.isAlive()) {
                    processHandle.destroyForcibly();
                }
            }
        });
    }

    private boolean processAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private void writeMetadata() {
        if (metadata == null) {
            return;
        }
        try {
            metadata.write(metadataPath);
        } catch (IOException exception) {
            logWriter.error("webcraft metadata write", exception);
        }
    }

    private void deleteMetadata() {
        try {
            Files.deleteIfExists(metadataPath);
        } catch (IOException exception) {
            logWriter.error("webcraft metadata delete", exception);
        }
    }

    private HttpServer createServer(InetSocketAddress address) throws IOException {
        if (config.server().useHttps()) {
            HttpsServer httpsServer = HttpsServer.create(address, 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(createSslContext()));
            return httpsServer;
        }
        return HttpServer.create(address, 0);
    }

    private static synchronized boolean acquireStartupLock() {
        if (startupLock != null && startupLock.isValid()) {
            return false;
        }

        try {
            Path lockPath = Path.of("config").resolve("webcraft-http.lock");
            Path parent = lockPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            startupLockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            startupLock = startupLockChannel.tryLock();
            if (startupLock == null) {
                closeStartupLockChannel();
                return false;
            }
            return true;
        } catch (IOException exception) {
            closeStartupLockChannel();
            return false;
        }
    }

    private static synchronized void releaseStartupLock() {
        try {
            if (startupLock != null) {
                startupLock.release();
            }
        } catch (IOException ignored) {
        } finally {
            startupLock = null;
            closeStartupLockChannel();
        }
    }

    private static void closeStartupLockChannel() {
        try {
            if (startupLockChannel != null) {
                startupLockChannel.close();
            }
        } catch (IOException ignored) {
        } finally {
            startupLockChannel = null;
        }
    }

    private SSLContext createSslContext() throws IOException {
        String keystorePath = config.server().keystorePath();
        String keystorePassword = config.server().keystorePassword();
        if (keystorePath.isBlank() || keystorePassword.isBlank()) {
            throw new IOException("HTTPS is enabled but keystore_path or keystore_password is missing");
        }

        try (InputStream inputStream = Files.newInputStream(Path.of(keystorePath))) {
            KeyStore keyStore = KeyStore.getInstance(config.server().keystoreType());
            keyStore.load(inputStream, keystorePassword.toCharArray());

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, keystorePassword.toCharArray());

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
            return sslContext;
        } catch (Exception exception) {
            throw new IOException("Failed to initialize HTTPS context", exception);
        }
    }
}

