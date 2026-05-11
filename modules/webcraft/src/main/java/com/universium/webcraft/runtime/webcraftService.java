package com.universium.webcraft.runtime;

import com.universium.webcraft.config.webcraftRuntimeConfigLoader;
import com.universium.webcraft.playerinfo.PlayerInfoData;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class webcraftService {
    private final Path configPath;
    private final webcraftPlayerDataProvider playerDataProvider;
    private final Supplier<Long> startedAtSupplier;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<Thread> bootstrapThread = new AtomicReference<>();
    private webcraftHttpServer server;

    public webcraftService(Path configPath, webcraftPlayerDataProvider playerDataProvider, Supplier<Long> startedAtSupplier) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.playerDataProvider = Objects.requireNonNull(playerDataProvider, "playerDataProvider");
        this.startedAtSupplier = Objects.requireNonNull(startedAtSupplier, "startedAtSupplier");
    }

    public synchronized void start() throws IOException {
        if (started.get()) {
            return;
        }
        webcraftBootstrap bootstrap = new webcraftBootstrap(configPath, playerDataProvider, startedAtSupplier.get());
        server = bootstrap.buildServer();
        server.start();
        started.set(true);
    }

    public synchronized void startAsync() {
        if (started.get() || bootstrapThread.get() != null) {
            return;
        }

        Thread thread = new Thread(() -> {
            long startedAt = startedAtSupplier.get();
            try {
                long buildStartedAt = System.nanoTime();
                webcraftBootstrap bootstrap = new webcraftBootstrap(configPath, playerDataProvider, startedAt);
                webcraftHttpServer builtServer = bootstrap.buildServer();
                long buildMs = Math.max(0L, (System.nanoTime() - buildStartedAt) / 1_000_000L);
                System.out.println("[WebCraft] HTTP bootstrap built in " + buildMs + "ms");
                synchronized (webcraftService.this) {
                    if (started.get()) {
                        return;
                    }
                    long serverStartedAt = System.nanoTime();
                    server = builtServer;
                    server.start();
                    long startMs = Math.max(0L, (System.nanoTime() - serverStartedAt) / 1_000_000L);
                    System.out.println("[WebCraft] HTTP server start() finished in " + startMs + "ms");
                    started.set(true);
                }
            } catch (IOException exception) {
                System.out.println("webcraft HTTP bootstrap failed: " + exception.getMessage());
            } finally {
                bootstrapThread.set(null);
            }
        }, "webcraft-http-bootstrap");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        bootstrapThread.set(thread);
        System.out.println("[WebCraft] HTTP bootstrap scheduled in background");
        thread.start();
    }

    public synchronized void stop() {
        Thread thread = bootstrapThread.getAndSet(null);
        if (thread != null) {
            thread.interrupt();
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        started.set(false);
    }

    public synchronized boolean isRunning() {
        return started.get();
    }

    public synchronized int boundPort() {
        return server == null ? -1 : server.boundPort();
    }

    public static webcraftService fromConfig(Path configPath, webcraftPlayerDataProvider playerDataProvider) {
        return new webcraftService(configPath, playerDataProvider, System::currentTimeMillis);
    }
}
