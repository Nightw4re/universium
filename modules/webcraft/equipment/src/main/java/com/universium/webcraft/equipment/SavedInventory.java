package com.universium.webcraft.equipment;

import com.mojang.datafixers.DataFixer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

record SavedInventory(int selectedSlot, Map<Integer, ItemStack> slots) {
    private static final int ARMOR_START = 100;
    private static final int OFFHAND = 150;
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;

    static CompoundTag read(Path directory, UUID player, DataFixer fixer) throws IOException {
        Path primary = directory.resolve(player + ".dat");
        IOException primaryFailure = null;
        try {
            return readFile(primary, fixer);
        } catch (NoSuchFileException ignored) {
            // Minecraft also keeps the previous save under .dat_old.
        } catch (IOException exception) {
            primaryFailure = exception;
        }

        try {
            return readFile(directory.resolve(player + ".dat_old"), fixer);
        } catch (NoSuchFileException ignored) {
            if (primaryFailure != null) throw primaryFailure;
            return null;
        } catch (IOException exception) {
            if (primaryFailure != null) exception.addSuppressed(primaryFailure);
            throw exception;
        }
    }

    private static CompoundTag readFile(Path file, DataFixer fixer) throws IOException {
        try {
            var tag = NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES));
            var updated = DataFixTypes.PLAYER.updateToCurrentVersion(fixer, tag, NbtUtils.getDataVersion(tag, -1));
            if (!(updated.get("Inventory") instanceof ListTag items)
                    || (!items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND)) {
                throw new IOException("Invalid Inventory list in " + file.getFileName());
            }
            return updated;
        } catch (RuntimeException exception) {
            throw new IOException("Could not decode " + file.getFileName(), exception);
        }
    }

    static SavedInventory decode(CompoundTag tag, HolderLookup.Provider registries) {
        int selectedSlot = tag.getInt("SelectedItemSlot");
        var slots = new HashMap<Integer, ItemStack>();
        var items = tag.getList("Inventory", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            var item = items.getCompound(index);
            if (!item.contains("Slot", Tag.TAG_BYTE)) continue;
            int slot = Byte.toUnsignedInt(item.getByte("Slot"));
            if (slot < 36 || (slot >= ARMOR_START && slot < ARMOR_START + 4) || slot == OFFHAND) {
                slots.put(slot, ItemStack.parse(registries, item).orElse(ItemStack.EMPTY));
            }
        }
        return new SavedInventory(selectedSlot >= 0 && selectedSlot < 9 ? selectedSlot : 0, Map.copyOf(slots));
    }

    ItemStack item(int slot) {
        return slots.getOrDefault(slot, ItemStack.EMPTY);
    }

    ItemStack equipment(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> item(selectedSlot);
            case OFFHAND -> item(OFFHAND);
            case HEAD, CHEST, LEGS, FEET -> item(ARMOR_START + slot.getIndex());
            default -> ItemStack.EMPTY;
        };
    }
}
