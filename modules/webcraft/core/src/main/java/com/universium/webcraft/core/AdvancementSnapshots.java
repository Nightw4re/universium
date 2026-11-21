package com.universium.webcraft.core;

import com.universium.webcraft.core.mixin.PlayerAdvancementsAccessor;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Captures the catalog and live progress on the Minecraft server thread. */
final class AdvancementSnapshots {
    private final MinecraftServer server;
    private final Supplier<PlayerRoster> roster;
    private AdvancementTree cachedTree;
    private List<Entry> catalog;

    AdvancementSnapshots(MinecraftServer server, Supplier<PlayerRoster> roster) {
        this.server = server;
        this.roster = roster;
    }

    Snapshot capture(String name) {
        var manager = server.getAdvancements();
        var tree = manager.tree();
        if (cachedTree != tree) {
            catalog = visibleCatalog(tree.nodes());
            cachedTree = tree;
        }
        if (name == null) return new Snapshot(catalog, null, Set.of());
        var known = roster.get().findByName(name);
        if (known == null) return null;

        var online = server.getPlayerList().getPlayer(known.uuid());
        if (online == null) return new Snapshot(catalog, known, null);

        var progress = ((PlayerAdvancementsAccessor) online.getAdvancements()).webcraft$getProgress();
        var completed = new HashSet<ResourceLocation>();
        for (var entry : catalog) {
            var advancement = progress.get(manager.get(entry.id()));
            if (advancement != null && advancement.isDone()) completed.add(entry.id());
        }
        return new Snapshot(catalog, known, Set.copyOf(completed));
    }

    static List<Entry> visibleCatalog(Collection<AdvancementNode> advancements) {
        var entries = new ArrayList<Entry>();
        for (var node : advancements) {
            var holder = node.holder();
            var display = holder.value().display().orElse(null);
            if (display == null || display.isHidden() || holder.id().getPath().startsWith("recipes/")) continue;
            entries.add(new Entry(holder.id(), display.getTitle().getString(), display.getDescription().getString(),
                    display.getType().getSerializedName(), holder.value().requirements()));
        }
        entries.sort(Comparator.comparing(entry -> entry.id().toString()));
        return List.copyOf(entries);
    }

    record Entry(ResourceLocation id, String title, String description, String frame, AdvancementRequirements requirements) {}

    // A null completed set means the player was offline and the worker must read the save.
    record Snapshot(List<Entry> catalog, PlayerRoster.NamedPlayer player, Set<ResourceLocation> completed) {}
}
