package com.universium.webcraft.api;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A change signal for a read route backed by stable snapshots. Publish the new snapshot first,
 * then call {@link #changed()}. Snapshot reads and notifications may occur on different threads.
 */
public final class WebCraftChanges {
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Creates a signal owned by a live route. */
    public WebCraftChanges() {}

    /** Signals listeners to read the latest snapshot. Callbacks must return promptly and must not throw. */
    public void changed() {
        listeners.forEach(Runnable::run);
    }

    /** Registers a callback until the returned registration is closed. A concurrent signal may still invoke it. */
    public Registration listen(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        Runnable registration = listener::run;
        listeners.add(registration);
        return () -> listeners.remove(registration);
    }

    /** A removable change listener. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        /** Removes the listener. Safe to call repeatedly. */
        @Override
        void close();
    }
}
