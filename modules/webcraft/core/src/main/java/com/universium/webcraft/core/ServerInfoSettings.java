package com.universium.webcraft.core;

import net.neoforged.neoforge.common.ModConfigSpec;

final class ServerInfoSettings {
    static final String FILE_NAME = "webcraft-server-info.toml";

    final ModConfigSpec spec;
    final ModConfigSpec.BooleanValue enabled;
    final ModConfigSpec.EnumValue<FeedExposure> exposure;
    final ModConfigSpec.BooleanValue loopbackOnly;
    final ModConfigSpec.EnumValue<ApiTransport> transport;
    final ModConfigSpec.ConfigValue<String> serverName;
    final ModConfigSpec.ConfigValue<String> joinAddress;
    final ModConfigSpec.BooleanValue publishPlayersOnline;
    final ModConfigSpec.BooleanValue publishMotd;
    final ModConfigSpec.BooleanValue publishServerIcon;
    final ModConfigSpec.BooleanValue publishUptime;
    final ModConfigSpec.BooleanValue publishTotalUptime;
    final ModConfigSpec.BooleanValue publishWorldAge;
    final ModConfigSpec.ConfigValue<String> worldStartTimestamp;
    final ModConfigSpec.BooleanValue publishWorldTime;
    final ModConfigSpec.BooleanValue publishModpackName;
    final ModConfigSpec.BooleanValue publishModpackVersion;
    final ModConfigSpec.EnumValue<ModpackMetadata.Source> modpackSource;
    final ModConfigSpec.ConfigValue<String> modpackFile;
    final ModConfigSpec.ConfigValue<String> modpackNameKey;
    final ModConfigSpec.ConfigValue<String> modpackVersionKey;

    ServerInfoSettings() {
        var builder = new ModConfigSpec.Builder();
        enabled = builder.comment("Expose the server information feed.").define("enabled", false);
        exposure = builder.comment("Use the public route or the internal route protected by WEBCRAFT_FEED_TOKEN.")
                .defineEnum("exposure", FeedExposure.PUBLIC);
        loopbackOnly = builder.comment("Restrict the server information feed to direct loopback connections.")
                .define("loopbackOnly", false);
        transport = builder.comment("Override apiTransport for this feed.").defineEnum("transport", ApiTransport.INHERIT);
        serverName = builder.comment("Public display name. Leave blank to omit it.").define("name", "");
        joinAddress = builder.comment("Public Minecraft address players use to join. Leave blank to omit it.")
                .define("joinAddress", "");
        publishPlayersOnline = builder.comment("Publish the current online player count and the server's player limit.")
                .define("publishPlayersOnline", false);
        publishMotd = builder.comment("Publish the Minecraft server list message of the day.").define("publishMotd", false);
        publishServerIcon = builder.comment("Publish the Minecraft server list icon when available.")
                .define("publishServerIcon", false);
        publishUptime = builder.comment("Publish elapsed seconds since this server session became ready.")
                .define("publishUptime", false);
        publishTotalUptime = builder.comment("Track and publish cumulative uptime for this world while enabled.",
                        "Checkpoints are written about every 10 seconds and on a clean stop.")
                .define("publishTotalUptime", false);
        publishWorldAge = builder.comment("Publish world age in whole minutes and completed 24-hour periods, including offline time.")
                .define("publishWorldAge", false);
        worldStartTimestamp = builder.comment("Verified ISO-8601 start timestamp to supply or correct the start of an existing world.",
                        "Set this only in this world's serverconfig/webcraft-server-info.toml. Leave blank when unknown.")
                .define("worldStartTimestamp", "");
        publishWorldTime = builder.comment("Publish the current time of day in the Overworld as a tick from 0 to 23999.")
                .define("publishWorldTime", false);
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
        builder.pop();
        spec = builder.build();
    }
}
