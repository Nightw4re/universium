package com.universium.webcraft.marketplace;

import com.universium.webcraft.runtime.webcraftAddonRegistry;
import com.universium.webcraft.runtime.webcraftRequest;
import com.universium.webcraft.runtime.webcraftResponse;
import com.universium.webcraft.runtime.webcraftRouteDefinition;
import net.neoforged.fml.common.Mod;

import java.util.List;
import java.util.Map;

@Mod(webcraftMarketplaceMod.MOD_ID)
public final class webcraftMarketplaceMod {
    public static final String MOD_ID = "webcraft-marketplace";

    public webcraftMarketplaceMod() {
        webcraftAddonRegistry.registerRoute(new webcraftRouteDefinition(
                "GET",
                "/marketplace/hello",
                "exact",
                List.of(),
                this::helloWorld,
                this::helloWorld,
                Map.of()
        ));
    }

    private webcraftResponse helloWorld(webcraftRequest request) {
        return webcraftResponse.json("{\"success\":true,\"data\":{\"message\":\"hello from webcraft-marketplace\"}}");
    }
}
