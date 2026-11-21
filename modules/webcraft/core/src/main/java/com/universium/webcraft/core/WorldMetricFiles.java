package com.universium.webcraft.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.IOUtilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

final class WorldMetricFiles {
    private WorldMetricFiles() {}

    // Keep the vanilla SavedData envelope so existing worlds can be read in place.
    static Optional<CompoundTag> read(Path file) throws IOException {
        if (Files.isDirectory(file.getParent())) {
            IOUtilities.tryCleanupTempFiles(file.getParent(), file.getFileName().toString());
        }
        try (var input = Files.newInputStream(file)) {
            var root = NbtIo.readCompressed(input, NbtAccounter.create(64 * 1024));
            if (!root.contains("data", Tag.TAG_COMPOUND)) {
                throw new IOException("Missing saved data in " + file);
            }
            return Optional.of(root.getCompound("data"));
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (NbtException | ReportedNbtException exception) {
            throw new IOException("Invalid NBT in " + file, exception);
        }
    }

    static void write(Path file, CompoundTag data) throws IOException {
        Files.createDirectories(file.getParent());
        var root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        // Do this synchronously so the game process can exit immediately after the checkpoint.
        IOUtilities.writeNbtCompressed(root, file);
    }
}
