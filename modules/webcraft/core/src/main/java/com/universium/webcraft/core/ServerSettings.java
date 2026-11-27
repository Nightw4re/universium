package com.universium.webcraft.core;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumSet;
import java.util.List;

final class ServerSettings {
    final ModConfigSpec spec;
    final ModConfigSpec.BooleanValue listenerEnabled;
    final ModConfigSpec.BooleanValue singleplayerEnabled;
    final ModConfigSpec.BooleanValue singleplayerAllowNetwork;
    final ModConfigSpec.ConfigValue<String> bindAddress;
    final ModConfigSpec.IntValue port;
    final ModConfigSpec.ConfigValue<List<? extends String>> enabledModules;
    final ModConfigSpec.ConfigValue<String> viewerUuid;
    final ModConfigSpec.BooleanValue apiDocsEnabled;
    final ModConfigSpec.BooleanValue accountLinksEnabled;
    final ModConfigSpec.BooleanValue accountLinksAllowRemote;
    final ModConfigSpec.BooleanValue playerHistoryEnabled;
    final ModConfigSpec.BooleanValue onlinePlayersEnabled;
    final ModConfigSpec.BooleanValue playerRosterEnabled;
    final ModConfigSpec.BooleanValue playerPrivacyEnabled;
    final ModConfigSpec.BooleanValue playerHistoryLoopbackOnly;
    final ModConfigSpec.BooleanValue onlinePlayersLoopbackOnly;
    final ModConfigSpec.BooleanValue playerRosterLoopbackOnly;
    final ModConfigSpec.EnumValue<ApiTransport> apiTransport;
    final ModConfigSpec.ConfigValue<List<? extends String>> routeTransports;
    final ModConfigSpec.ConfigValue<List<? extends String>> websocketOrigins;
    final ModConfigSpec.EnumValue<ApiTransport> playerHistoryTransport;
    final ModConfigSpec.EnumValue<ApiTransport> onlinePlayersTransport;
    final ModConfigSpec.EnumValue<ApiTransport> playerRosterTransport;
    final ModConfigSpec.BooleanValue advancementsEnabled;
    final ModConfigSpec.BooleanValue advancementsLoopbackOnly;
    final ModConfigSpec.EnumValue<ApiTransport> advancementsTransport;

    ServerSettings() {
        var builder = new ModConfigSpec.Builder();
        listenerEnabled = builder.comment("Start the WebCraft API listener on dedicated servers.")
                .define("listenerEnabled", false);
        singleplayerEnabled = builder.comment("Start the WebCraft API listener while a singleplayer world is open.")
                .define("singleplayerEnabled", false);
        singleplayerAllowNetwork = builder.comment("Use bindAddress in singleplayer instead of forcing 127.0.0.1.")
                .define("singleplayerAllowNetwork", false);
        bindAddress = builder.comment("API bind address. Use a private interface for a remote reverse proxy.")
                .define("bindAddress", "127.0.0.1");
        port = builder.comment("Fixed API port shared by HTTP and WebSocket.").defineInRange("port", 8765, 1, 65535);
        apiTransport = builder.comment("Default transport for API routes and discovery.")
                .defineEnum("apiTransport", ApiTransport.HTTP, EnumSet.of(ApiTransport.HTTP, ApiTransport.WEBSOCKET, ApiTransport.BOTH));
        routeTransports = builder.comment("Exact path overrides. These take precedence over feed and global settings.")
                .defineListAllowEmpty("routeTransports", List.of(), () -> "/api/v1/modules=BOTH", value -> value instanceof String);
        websocketOrigins = builder.comment("Additional browser origins for WebSocket, such as the HTTPS frontend origin.")
                .defineListAllowEmpty("websocketOrigins", List.of(), () -> "https://webcraft.example.org", value -> value instanceof String);
        enabledModules = builder.comment("Installed WebCraft modules that may expose API routes.")
                .defineListAllowEmpty("enabledModules", List.of(), () -> "modid:module", value -> value instanceof String);
        viewerUuid = builder.comment("UUID of the consenting player authorized by WEBCRAFT_VIEWER_TOKEN.")
                .define("viewerUuid", "");
        apiDocsEnabled = builder.comment("Expose public Swagger UI and the OpenAPI document.")
                .define("apiDocsEnabled", false);
        accountLinksEnabled = builder.comment("Enable /webcraft link and the HTTP redemption endpoint on dedicated servers.",
                        "Requires the WEBCRAFT_ACCOUNT_LINK_TOKEN environment variable and an active API listener.")
                .define("accountLinksEnabled", false);
        accountLinksAllowRemote = builder.comment("Allow account link redemption from non-loopback TCP peers.",
                        "Enable only on a protected private connection. Never forward the internal route from a public proxy.")
                .define("accountLinksAllowRemote", false);
        playerHistoryEnabled = builder.comment("Record player joins and expose the history through the token-protected internal feed.")
                .define("playerHistoryEnabled", false);
        playerHistoryLoopbackOnly = builder.comment("Restrict the player history feed to direct loopback connections.")
                .define("playerHistoryLoopbackOnly", false);
        playerHistoryTransport = builder.comment("Override apiTransport for the player history feed.")
                .defineEnum("playerHistoryTransport", ApiTransport.INHERIT);
        onlinePlayersEnabled = builder.comment("Expose listed online players and their ping through a token-protected internal feed.")
                .define("onlinePlayersEnabled", false);
        onlinePlayersLoopbackOnly = builder.comment("Restrict the online player feed to direct loopback connections.")
                .define("onlinePlayersLoopbackOnly", true);
        onlinePlayersTransport = builder.comment("Override apiTransport for the online player feed.")
                .defineEnum("onlinePlayersTransport", ApiTransport.INHERIT);
        playerRosterEnabled = builder.comment("Expose identities from this world's saved player records and track future logins.",
                        "Uses a token-protected internal feed.")
                .define("playerRosterEnabled", false);
        playerPrivacyEnabled = builder.comment("Enable the HTTP-only machine endpoint for storing player privacy settings.",
                        "The endpoint always requires loopback and the WebCraft feed token.")
                .define("playerPrivacyEnabled", false);
        playerRosterLoopbackOnly = builder.comment("Restrict the player roster feed to direct loopback connections.")
                .define("playerRosterLoopbackOnly", true);
        playerRosterTransport = builder.comment("Override apiTransport for the player roster feed.")
                .defineEnum("playerRosterTransport", ApiTransport.INHERIT);
        advancementsEnabled = builder.comment("Expose the visible advancement catalog and selected player progress.",
                        "Uses the token-protected internal API and records player identities for name lookups.")
                .define("advancementsEnabled", false);
        advancementsLoopbackOnly = builder.comment("Restrict the advancements route to direct loopback connections.")
                .define("advancementsLoopbackOnly", true);
        advancementsTransport = builder.comment("Override apiTransport for the advancements route.")
                .defineEnum("advancementsTransport", ApiTransport.INHERIT);
        spec = builder.build();
    }

    boolean listenerEnabled(boolean dedicated) {
        return (dedicated ? listenerEnabled : singleplayerEnabled).get();
    }

    String listenerHost(boolean dedicated) {
        return dedicated || singleplayerAllowNetwork.get() ? bindAddress.get() : "127.0.0.1";
    }
}
