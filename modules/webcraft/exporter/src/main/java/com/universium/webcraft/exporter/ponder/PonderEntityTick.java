package com.universium.webcraft.exporter.ponder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/** Keeps reentrant entity additions and removals out of the active Ponder export iteration. */
public final class PonderEntityTick implements AutoCloseable {
    private static final ThreadLocal<PonderEntityTick> ACTIVE = new ThreadLocal<>();
    private final Set<Object> dirtyBlockEntities = Collections.newSetFromMap(new IdentityHashMap<>());

    public PonderEntityTick() {
        if (ACTIVE.get() != null) throw new IllegalStateException("A Ponder export tick is already active");
        ACTIVE.set(this);
    }

    public static <T> Iterator<T> iterator(List<T> entities) {
        if (ACTIVE.get() == null) return entities.iterator();
        var snapshot = new ArrayList<>(entities);
        return new Iterator<>() {
            private int nextIndex;
            private T current;
            private boolean canRemove;

            @Override
            public boolean hasNext() {
                while (nextIndex < snapshot.size() && !entities.contains(snapshot.get(nextIndex))) nextIndex++;
                return nextIndex < snapshot.size();
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                current = snapshot.get(nextIndex++);
                canRemove = true;
                return current;
            }

            @Override
            public void remove() {
                if (!canRemove) throw new IllegalStateException();
                entities.removeIf(entity -> entity == current);
                canRemove = false;
            }
        };
    }

    public static void recordBlockEntityChange(Object entity) {
        var tick = ACTIVE.get();
        if (tick != null) tick.dirtyBlockEntities.add(entity);
    }

    Set<Object> dirtyBlockEntities() {
        return Collections.unmodifiableSet(dirtyBlockEntities);
    }

    @Override
    public void close() {
        ACTIVE.remove();
    }
}
