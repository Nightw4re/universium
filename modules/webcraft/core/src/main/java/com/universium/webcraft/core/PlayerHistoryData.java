package com.universium.webcraft.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PlayerHistoryData extends SavedData {
    static final String FILE_NAME = "webcraft_player_history";
    static final Factory<PlayerHistoryData> FACTORY = new Factory<>(PlayerHistoryData::new, PlayerHistoryData::load);

    private final Map<UUID, Player> players = new LinkedHashMap<>();

    record Player(UUID uuid, String name, long lastSeenAt) {}

    static PlayerHistoryData load(CompoundTag tag, HolderLookup.Provider registries) {
        var history = new PlayerHistoryData();
        var entries = tag.getList("players", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.getCompound(index);
            if (entry.hasUUID("uuid") && entry.contains("name", Tag.TAG_STRING)) {
                var player = new Player(entry.getUUID("uuid"), entry.getString("name"), entry.getLong("lastSeenAt"));
                history.players.put(player.uuid(), player);
            }
        }
        return history;
    }

    void recordJoin(UUID uuid, String name, long lastSeenAt) {
        var previous = players.get(uuid);
        if (previous == null || lastSeenAt >= previous.lastSeenAt()) {
            players.put(uuid, new Player(uuid, name, lastSeenAt));
            setDirty();
        }
    }

    List<Player> players() {
        return List.copyOf(players.values());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new ListTag();
        for (var player : players.values()) {
            var entry = new CompoundTag();
            entry.putUUID("uuid", player.uuid());
            entry.putString("name", player.name());
            entry.putLong("lastSeenAt", player.lastSeenAt());
            entries.add(entry);
        }
        tag.put("players", entries);
        return tag;
    }
}
