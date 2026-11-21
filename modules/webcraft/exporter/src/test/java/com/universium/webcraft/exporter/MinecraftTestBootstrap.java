package com.universium.webcraft.exporter;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

import java.util.List;
import java.util.Map;

final class MinecraftTestBootstrap {
    private MinecraftTestBootstrap() {}

    static void initialize() {
        // NeoForge's vanilla feature flag bootstrap expects a discovered mod list.
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
