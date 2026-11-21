package com.universium.webcraft.core.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Reads existing progress without creating entries via PlayerAdvancements#getOrStartProgress. */
@Mixin(PlayerAdvancements.class)
public interface PlayerAdvancementsAccessor {
    /** The server-thread progress map. Treat it as read-only. */
    @Accessor("progress")
    Map<AdvancementHolder, AdvancementProgress> webcraft$getProgress();
}
