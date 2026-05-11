package com.universium.webcraft.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public final class webcraftLogWriter {
    private final boolean accessEnabled;
    private final boolean errorEnabled;
    private final Path accessFile;
    private final Path errorFile;

    public webcraftLogWriter(boolean accessEnabled, boolean errorEnabled, Path accessFile, Path errorFile) {
        this.accessEnabled = accessEnabled;
        this.errorEnabled = errorEnabled;
        this.accessFile = Objects.requireNonNull(accessFile, "accessFile");
        this.errorFile = Objects.requireNonNull(errorFile, "errorFile");
    }

    public void access(String message) {
        if (accessEnabled) {
            write(accessFile, "ACCESS", message);
        }
    }

    public void error(String message) {
        if (errorEnabled) {
            write(errorFile, "ERROR", message);
        }
    }

    public void error(String message, Throwable throwable) {
        if (errorEnabled) {
            write(errorFile, "ERROR", message + " :: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    public void info(String message) {
        if (accessEnabled || errorEnabled) {
            write(accessFile, "INFO", message);
        }
    }

    private void write(Path file, String level, String message) {
        try {
            Files.createDirectories(file.getParent());
            String line = "[" + Instant.now() + "] [" + level + "] " + message + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8, Files.exists(file) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException exception) {
            System.err.println("webcraft log write failed: " + exception.getMessage());
        }
    }
}
