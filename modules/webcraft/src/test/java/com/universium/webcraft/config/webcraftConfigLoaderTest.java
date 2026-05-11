package com.universium.webcraft.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class webcraftConfigLoaderTest {
    @Test
    void parsesServerAndFrontendValues() {
        String toml = """
                [server]
                enabled = true
                host = "0.0.0.0"
                port = 8090
                public_url = "https://example.invalid"
                minecraft_version = "1.21.1"
                neoforge_build = 224
                server_name = "Universium"
                generate_resources_at_start = false
                resource_packs_web_order = ["beta", "alpha"]

                [frontend]
                mode = "external"
                theme = "universum"
                """;

        webcraftConfig config = new webcraftConfigLoader().parse(toml);

        assertTrue(config.server().enabled());
        assertEquals("0.0.0.0", config.server().host());
        assertEquals(8090, config.server().port());
        assertEquals("https://example.invalid", config.server().publicUrl());
        assertEquals("1.21.1", config.server().minecraftVersion());
        assertEquals(224, config.server().neoforgeBuild());
        assertEquals("Universium", config.server().serverName());
        assertTrue(!config.server().generateResourcesAtStart());
        assertEquals(List.of("beta", "alpha"), config.server().resourcePacksWebOrder());
        assertEquals("external", config.frontend().mode());
        assertEquals("universum", config.frontend().theme());
    }
}

