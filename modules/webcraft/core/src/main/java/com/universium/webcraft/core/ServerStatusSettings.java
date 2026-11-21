package com.universium.webcraft.core;

import net.neoforged.neoforge.common.ModConfigSpec;

final class ServerStatusSettings {
    static final String FILE_NAME = "webcraft-server-status.toml";

    final ModConfigSpec spec;
    final ModConfigSpec.BooleanValue enabled;
    final ModConfigSpec.EnumValue<FeedExposure> exposure;
    final ModConfigSpec.BooleanValue loopbackOnly;
    final ModConfigSpec.EnumValue<ApiTransport> transport;

    ServerStatusSettings() {
        var builder = new ModConfigSpec.Builder();
        enabled = builder.comment("Expose the server lifecycle feed.").define("enabled", false);
        exposure = builder.comment("Use the public route or the internal route protected by WEBCRAFT_FEED_TOKEN.")
                .defineEnum("exposure", FeedExposure.INTERNAL);
        loopbackOnly = builder.comment("Restrict the server status feed to direct loopback connections.")
                .define("loopbackOnly", false);
        transport = builder.comment("Override apiTransport for this feed.").defineEnum("transport", ApiTransport.INHERIT);
        spec = builder.build();
    }
}
