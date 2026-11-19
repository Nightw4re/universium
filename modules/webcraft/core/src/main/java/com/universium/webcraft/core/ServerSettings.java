package com.universium.webcraft.core;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

final class ServerSettings {
    final ModConfigSpec spec;
    final ModConfigSpec.BooleanValue listenerEnabled;
    final ModConfigSpec.ConfigValue<String> bindAddress;
    final ModConfigSpec.IntValue port;
    final ModConfigSpec.ConfigValue<List<? extends String>> enabledModules;
    final ModConfigSpec.ConfigValue<String> viewerUuid;
    final ModConfigSpec.BooleanValue serverInfoEnabled;
    final ModConfigSpec.BooleanValue apiDocsEnabled;
    final ModConfigSpec.ConfigValue<String> serverName;
    final ModConfigSpec.ConfigValue<String> joinAddress;
    final ModConfigSpec.BooleanValue publishPlayersOnline;
    final ModConfigSpec.BooleanValue publishModpackName;
    final ModConfigSpec.BooleanValue publishModpackVersion;
    final ModConfigSpec.EnumValue<ModpackMetadata.Source> modpackSource;
    final ModConfigSpec.ConfigValue<String> modpackFile;
    final ModConfigSpec.ConfigValue<String> modpackNameKey;
    final ModConfigSpec.ConfigValue<String> modpackVersionKey;

    ServerSettings() {
        var builder = new ModConfigSpec.Builder();
        listenerEnabled = builder.comment("Start the built-in HTTP listener on dedicated servers.")
                .define("listenerEnabled", false);
        bindAddress = builder.comment("HTTP bind address. Use a private interface for a remote reverse proxy.")
                .define("bindAddress", "127.0.0.1");
        port = builder.comment("Fixed HTTP port.").defineInRange("port", 8765, 1, 65535);
        enabledModules = builder.comment("Installed WebCraft modules that may expose REST routes.")
                .defineListAllowEmpty("enabledModules", List.of(), () -> "modid:module", value -> value instanceof String);
        viewerUuid = builder.comment("UUID of the consenting player authorized by WEBCRAFT_VIEWER_TOKEN.")
                .define("viewerUuid", "");
        apiDocsEnabled = builder.comment("Expose public Swagger UI and the OpenAPI document.")
                .define("apiDocsEnabled", false);
        builder.push("serverInfo");
        serverInfoEnabled = builder.comment("Expose the public /api/v1/server-info route.").define("enabled", false);
        serverName = builder.comment("Public display name. Leave blank to omit it.").define("name", "");
        joinAddress = builder.comment("Public Minecraft address players use to join. Leave blank to omit it.")
                .define("joinAddress", "");
        publishPlayersOnline = builder.comment("Publish the current online player count.")
                .define("publishPlayersOnline", false);
        builder.push("modpack");
        publishModpackName = builder.define("publishName", false);
        publishModpackVersion = builder.define("publishVersion", false);
        modpackSource = builder.comment("AUTO uses the first usable source. Other modes use only the selected source.")
                .defineEnum("source", ModpackMetadata.Source.AUTO);
        modpackFile = builder.comment("For CUSTOM, a JSON or TOML file relative to the Minecraft instance directory.")
                .define("file", "");
        modpackNameKey = builder.comment("For CUSTOM, the dot-separated path to the name string.").define("nameKey", "name");
        modpackVersionKey = builder.comment("For CUSTOM, the dot-separated path to the version string.")
                .define("versionKey", "version");
        builder.pop(2);
        spec = builder.build();
    }
}
