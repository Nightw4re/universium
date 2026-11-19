package com.universium.webcraft.runtime;

import java.nio.file.Path;
import java.util.Objects;

public record webcraftResourcePack(
        String id,
        String name,
        String path,
        int priority
) {
    public webcraftResourcePack {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        path = Objects.requireNonNull(path, "path");
    }

    public Path resolve(Path root) {
        return root.resolve(path).normalize();
    }
}
