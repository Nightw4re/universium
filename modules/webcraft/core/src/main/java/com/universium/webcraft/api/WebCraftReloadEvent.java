package com.universium.webcraft.api;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

/**
 * Fired on the NeoForge game bus and server thread while the WebCraft listener is closed.
 * Add-ons can refresh the data behind their registered routes before the listener reopens.
 */
public final class WebCraftReloadEvent extends Event {
    private final MinecraftServer server;

    /** Creates a reload event for the running server. */
    public WebCraftReloadEvent(MinecraftServer server) {
        this.server = server;
    }

    /** Returns the server whose WebCraft data is being refreshed. */
    public MinecraftServer server() {
        return server;
    }
}
