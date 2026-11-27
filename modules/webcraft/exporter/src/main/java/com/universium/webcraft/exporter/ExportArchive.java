package com.universium.webcraft.exporter;

import com.mojang.logging.LogUtils;
import com.universium.webcraft.archive.CatalogJsonBundles;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ExportArchive implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CAPACITY = 32;
    private static final Task WAKE = new Task(writer -> {}, true);

    private final ArrayBlockingQueue<Task> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final CompletableFuture<Boolean> completion = new CompletableFuture<>();
    private final PreviewAtlasWriter atlas;
    private final ExecutorService encoders = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)),
            Thread.ofPlatform().daemon().name("WebCraft preview encoder-", 1).factory());
    private long activeNanos;

    ExportArchive(Path output) {
        this(output, null);
    }

    ExportArchive(Path output, StaticPreviewWriter atlasCodec) {
        atlas = atlasCodec == null ? null : new PreviewAtlasWriter(output.getParent(), atlasCodec);
        Thread.ofPlatform().daemon().name("WebCraft preview archive").start(() -> {
            try {
                completion.complete(write(output));
            } catch (Throwable failure) {
                if (state.get() == State.CANCELLED) {
                    if (!(failure instanceof Cancelled) || failure.getSuppressed().length != 0) {
                        LOGGER.warn("Could not cleanly cancel the WebCraft preview archive", failure);
                    }
                    completion.complete(false);
                } else {
                    completion.completeExceptionally(failure);
                }
            } finally {
                encoders.shutdownNow();
                queue.clear();
            }
        });
    }

    Work preparePreview(StaticPreviewWriter previews, String key, PngPixels pixels, boolean mayAnimate) {
        return atlas == null ? prepare(() -> previews.prepare(key, pixels))
                : writer -> atlas.write(writer, key, pixels, mayAnimate);
    }

    void markAnimated(String key) {
        if (atlas != null) atlas.markAnimated(key);
    }

    // Only the client thread submits work. Animation captures can reserve slots until their loop ends.
    boolean hasRoom(int slots) {
        return queue.remainingCapacity() >= slots;
    }

    void submit(Work work) {
        enqueue(new Task(work, false));
    }

    // The caller reserves an archive queue slot for the stack before starting its preparation.
    Work prepare(Preparation preparation) {
        var result = encoders.submit(preparation::prepare);
        return writer -> {
            try {
                result.get().write(writer);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for a preview to encode", exception);
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof IOException failure) throw failure;
                throw new IOException("Could not encode a preview", exception.getCause());
            }
        };
    }

    void finish(Work work) {
        enqueue(new Task(work, true));
    }

    private void enqueue(Task task) {
        if (!queue.offer(task)) {
            throw new IllegalStateException("WebCraft preview archive queue is full");
        }
    }

    boolean finished() throws IOException {
        try {
            return completion.getNow(false);
        } catch (CompletionException exception) {
            throw new IOException("Could not write the WebCraft preview archive", exception.getCause());
        }
    }

    // Read after finished() reports success, which publishes the worker's timings.
    long activeNanos() {
        return activeNanos;
    }

    private boolean write(Path output) throws IOException, InterruptedException {
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), "previews-", ".tmp");
        try {
            try (var atlasResource = atlas;
                 var archive = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                EntryWriter writer = (name, bytes) -> {
                    if (state.get() == State.CANCELLED) throw new Cancelled();
                    var entry = new ZipEntry(name);
                    if (name.endsWith(".png") || name.endsWith(".webp")) {
                        // PNG, APNG and WebP already contain compressed image data.
                        var crc = new CRC32();
                        crc.update(bytes);
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(bytes.length);
                        entry.setCrc(crc.getValue());
                    }
                    archive.putNextEntry(entry);
                    archive.write(bytes);
                    archive.closeEntry();
                };
                var bundles = new CatalogJsonBundles.Writer(writer::write);
                while (state.get() == State.OPEN) {
                    Task task = queue.take();
                    if (state.get() == State.CANCELLED) break;
                    long started = System.nanoTime();
                    task.work().write(bundles::write);
                    if (task.last()) {
                        if (atlasResource != null) atlasResource.finish(writer);
                        bundles.finish();
                    }
                    activeNanos += System.nanoTime() - started;
                    if (task.last()) {
                        break;
                    }
                }
            }
            // Cancellation and publication contend only for this state transition, never for filesystem I/O.
            if (state.compareAndSet(State.OPEN, State.PUBLISHING)) {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        Files.deleteIfExists(temporary);
        return false;
    }

    @Override
    public void close() {
        if (state.compareAndSet(State.OPEN, State.CANCELLED)) {
            queue.clear();
            if (!completion.isDone()) queue.offer(WAKE);
        }
    }

    @FunctionalInterface
    interface Work {
        void write(EntryWriter writer) throws IOException;
    }

    @FunctionalInterface
    interface Preparation {
        Work prepare() throws IOException;
    }

    @FunctionalInterface
    interface EntryWriter {
        void write(String name, byte[] bytes) throws IOException;
    }

    private record Task(Work work, boolean last) {}

    private static final class Cancelled extends IOException {}

    private enum State { OPEN, CANCELLED, PUBLISHING }
}
