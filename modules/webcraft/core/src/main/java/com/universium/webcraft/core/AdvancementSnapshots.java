package com.universium.webcraft.core;

import com.universium.webcraft.core.mixin.PlayerAdvancementsAccessor;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        var dates = new HashMap<ResourceLocation, Long>();
        for (var entry : catalog) {
            var advancement = progress.get(manager.get(entry.id()));
            if (advancement != null && advancement.isDone()) {
                completed.add(entry.id());
                var timestamp = completedAt(advancement, entry.requirements());
                if (timestamp != null) dates.put(entry.id(), timestamp);
            }
        }
        return new Snapshot(catalog, known, Set.copyOf(completed), Map.copyOf(dates));
    }

    static Long completedAt(AdvancementProgress progress, AdvancementRequirements requirements) {
        long latest = Long.MIN_VALUE;
        for (var alternatives : requirements.requirements()) {
            long earliest = Long.MAX_VALUE;
            for (var name : alternatives) {
                var criterion = progress.getCriterion(name);
                if (criterion != null && criterion.getObtained() != null) {
                    earliest = Math.min(earliest, criterion.getObtained().toEpochMilli());
                }
            }
            if (earliest == Long.MAX_VALUE) return null;
            latest = Math.max(latest, earliest);
        }
        return latest > 0 ? latest : null;
    }

    static List<Entry> visibleCatalog(Collection<AdvancementNode> advancements) {
        var entries = new ArrayList<Entry>();
        for (var node : advancements) {
            var holder = node.holder();
            var display = holder.value().display().orElse(null);
            if (!isVisible(node) || display == null) continue;
            var parent = node.parent();
            while (parent != null && !isVisible(parent)) parent = parent.parent();
            entries.add(new Entry(holder.id(), display.getTitle().getString(), display.getDescription().getString(),
                    display.getType().getSerializedName(), holder.value().requirements(),
                    node.root().holder().id(), node.root().holder().value().display()
                            .map(root -> root.getTitle().getString()).orElse(""),
                    display.getIcon().isEmpty() ? null : BuiltInRegistries.ITEM.getKey(display.getIcon().getItem()),
                    display.getX(), display.getY(), parent == null ? null : parent.holder().id()));
        }
        entries.sort(Comparator.comparing(entry -> entry.id().toString()));
        return List.copyOf(entries);
    }

    private static boolean isVisible(AdvancementNode node) {
        var holder = node.holder();
        return !holder.id().getPath().startsWith("recipes/")
                && holder.value().display().map(display -> !display.isHidden()).orElse(false);
    }

    record Entry(ResourceLocation id, String title, String description, String frame, AdvancementRequirements requirements,
                 ResourceLocation group, String groupTitle, ResourceLocation iconItemId, float x, float y,
                 ResourceLocation parentId) {}

    // A null completed set means the player was offline and the worker must read the save.
    record Snapshot(List<Entry> catalog, PlayerRoster.NamedPlayer player, Set<ResourceLocation> completed,
                    Map<ResourceLocation, Long> dates) {
        Snapshot(List<Entry> catalog, PlayerRoster.NamedPlayer player, Set<ResourceLocation> completed) {
            this(catalog, player, completed, Map.of());
        }
    }
}
