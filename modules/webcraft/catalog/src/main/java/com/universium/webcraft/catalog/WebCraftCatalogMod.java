package com.universium.webcraft.catalog;

import com.universium.webcraft.api.RegisterWebCraftModulesEvent;
import com.universium.webcraft.api.WebCraftPreviewPack;
import com.universium.webcraft.api.WebCraftReadRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

@Mod(WebCraftCatalogMod.MOD_ID)
public final class WebCraftCatalogMod {
    public static final String MOD_ID = "webcraft_catalog";
    private static final ResourceLocation MODULE = ResourceLocation.fromNamespaceAndPath(MOD_ID, "items");
    private volatile ItemCatalog catalog;

    public WebCraftCatalogMod(IEventBus modBus) {
        modBus.addListener(this::register);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, this::prepareCatalog);
    }

    private void register(RegisterWebCraftModulesEvent event) {
        WebCraftReadRoute.PublicRoute items = query -> {
            ItemCatalog current = catalog;
            return current == null ? Response.error(503, "catalog_not_ready") : current.items(query);
        };
        WebCraftReadRoute.PublicRoute icon = query -> {
            ItemCatalog current = catalog;
            return current == null ? Response.error(503, "catalog_not_ready") : current.icon(query);
        };
        WebCraftReadRoute.PublicRoute detail = query -> {
            ItemCatalog current = catalog;
            return current == null ? Response.error(503, "catalog_not_ready") : current.detail(query);
        };
        WebCraftReadRoute.PublicRoute effects = query -> {
            ItemCatalog current = catalog;
            return current == null ? Response.error(503, "catalog_not_ready") : current.effects(query);
        };
        event.register(MODULE, Map.of("items", items, "icon", icon, "detail", detail, "effects", effects));
    }

    private void prepareCatalog(ServerStartedEvent event) {
        if (event.getServer().isDedicatedServer()) {
            var registered = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
                    .map(BuiltInRegistries.ITEM::getKey).collect(Collectors.toUnmodifiableSet());
            catalog = new ItemCatalog(registered, new WebCraftPreviewPack(Path.of("webcraft-assets", "previews.zip")));
        }
    }
}
