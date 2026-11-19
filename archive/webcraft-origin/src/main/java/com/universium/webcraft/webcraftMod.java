package com.universium.webcraft;

import com.universium.webcraft.playerinfo.PlayerInfoData;
import com.universium.webcraft.playerinfo.PlayerInfoItem;
import com.universium.webcraft.config.webcraftConfig;
import com.universium.webcraft.config.webcraftRuntimeConfigLoader;
import com.universium.webcraft.runtime.webcraftService;
import com.universium.webcraft.runtime.webcraftPlayerDataProvider;
import com.universium.webcraft.runtime.webcraftProcessKiller;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(webcraftMod.MOD_ID)
public final class webcraftMod {
    public static final String MOD_ID = "webcraft";
    private static final Logger LOGGER = LoggerFactory.getLogger(webcraftMod.class);
    private static final Pattern USERCACHE_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern USERCACHE_ENTRY_PATTERN = Pattern.compile(
            "\\{[^\\}]*\"name\"\\s*:\\s*\"([^\"]+)\"[^\\}]*\"uuid\"\\s*:\\s*\"([^\"]+)\"[^\\}]*\\}"
    );
    private static final AtomicBoolean LISTENERS_REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean SERVER_STARTED = new AtomicBoolean(false);
    private static final String MISSINGNO_ID = "minecraft:missingno";
    private static final List<String> FLAT_BLOCK_ITEM_OVERRIDES = List.of(
            "poppy",
            "dandelion",
            "crafting_table",
            "acacia_sapling",
            "sugar_cane"
    );

    private webcraftService service;
    private final AtomicReference<net.minecraft.server.MinecraftServer> currentServer = new AtomicReference<>();

    public webcraftMod(IEventBus modBus) {
        maybeKillStaleDevProcess();
        if (LISTENERS_REGISTERED.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
            NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        }
    }

    private static void maybeKillStaleDevProcess() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("webcraft-server.toml");
        if (!Files.exists(configPath)) {
            return;
        }

        try {
            webcraftConfig config = new webcraftRuntimeConfigLoader().load(configPath).config();
            int killed = webcraftProcessKiller.killMatchingDevProcesses(config.server().killExistingOnStart());
            if (killed > 0) {
                System.out.println("webcraft killed " + killed + " stale process(es) before server start");
            }
        } catch (Exception ignored) {
        }
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        currentServer.set(event.getServer());

        if (service != null) {
            return;
        }

        Path configPath = FMLPaths.CONFIGDIR.get().resolve("webcraft-server.toml");
        webcraftPlayerDataProvider provider = new webcraftPlayerDataProvider() {
            @Override
            public PlayerInfoData getPlayerInfo(String playerName) {
                net.minecraft.server.MinecraftServer server = currentServer.get();
                if (server != null) {
                    ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
                    if (player != null) {
                        return buildPlayerInfo(player);
                    }
                }
                return buildPlayerInfoFromSavedProfile(playerName).orElseGet(() -> samplePlayer(playerName));
            }

            @Override
            public List<String> getPlayerNames() {
                return collectPlayerNames(currentServer.get());
            }

            @Override
            public List<String> getOnlinePlayerNames() {
                return collectOnlinePlayerNames(currentServer.get());
            }
        };

        service = new webcraftService(configPath, provider, System::currentTimeMillis);
        boolean bootstrapAsync = true;
        try {
            webcraftConfig config = new webcraftRuntimeConfigLoader().load(configPath).config();
            bootstrapAsync = config.server().httpBootstrapAsync();
        } catch (IOException ignored) {
        }
        if (bootstrapAsync) {
            service.startAsync();
        } else {
            try {
                service.start();
            } catch (IOException exception) {
                throw new RuntimeException("Failed to start webcraft HTTP server", exception);
            }
        }
        SERVER_STARTED.set(true);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        currentServer.set(null);
        if (service != null) {
            service.stop();
            service = null;
        }
        SERVER_STARTED.set(false);
    }

    private static List<String> collectPlayerNames(net.minecraft.server.MinecraftServer server) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(loadUserCacheNames());
        if (server == null) {
            return List.copyOf(names);
        }
        server.getPlayerList().getPlayers().forEach(player -> {
            String playerName = player.getGameProfile().getName();
            if (playerName != null && !playerName.isBlank()) {
                names.add(playerName);
            }
        });
        return List.copyOf(names);
    }

    private static List<String> collectOnlinePlayerNames(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        return server.getPlayerList().getPlayers().stream()
                .map(player -> player.getGameProfile().getName())
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> loadUserCacheNames() {
        Path userCache = FMLPaths.GAMEDIR.get().resolve("usercache.json");
        if (!Files.exists(userCache)) {
            return List.of();
        }
        try {
            String content = Files.readString(userCache, StandardCharsets.UTF_8);
            Matcher matcher = USERCACHE_NAME_PATTERN.matcher(content);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            while (matcher.find()) {
                String playerName = matcher.group(1);
                if (playerName != null && !playerName.isBlank()) {
                    names.add(playerName);
                }
            }
            return List.copyOf(names);
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static Optional<ProfileRecord> findUserCacheRecord(String playerName) {
        Path userCache = FMLPaths.GAMEDIR.get().resolve("usercache.json");
        if (!Files.exists(userCache)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(userCache, StandardCharsets.UTF_8);
            Matcher matcher = USERCACHE_ENTRY_PATTERN.matcher(content);
            while (matcher.find()) {
                String name = matcher.group(1);
                String uuid = matcher.group(2);
                if (name != null && name.equalsIgnoreCase(playerName) && uuid != null && !uuid.isBlank()) {
                    return Optional.of(new ProfileRecord(name, uuid));
                }
            }
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    private static Optional<PlayerInfoData> buildPlayerInfoFromSavedProfile(String playerName) {
        Optional<ProfileRecord> profileRecord = findUserCacheRecord(playerName);
        if (profileRecord.isEmpty()) {
            LOGGER.warn("webcraft no usercache record found for {}", playerName);
            return Optional.empty();
        }
        ProfileRecord record = profileRecord.get();
        Path playerFile = FMLPaths.GAMEDIR.get()
                .resolve("world")
                .resolve("playerdata")
                .resolve(record.uuid() + ".dat");
        LOGGER.info("webcraft loading offline player snapshot for {} from {}", playerName, playerFile);
        if (!Files.exists(playerFile)) {
            LOGGER.warn("webcraft offline player snapshot not found for {}", playerName);
            return Optional.empty();
        }
        try {
            CompoundTag saveTag = NbtIo.readCompressed(playerFile, NbtAccounter.unlimitedHeap());
            LOGGER.info("webcraft loaded offline snapshot for {} with keys {}", playerName, saveTag.getAllKeys());
            ListTag inventoryTag = saveTag.getList("Inventory", 10);
            LOGGER.info("webcraft offline inventory size for {} is {}", playerName, inventoryTag.size());
            List<PlayerInfoItem> inventory = parseInventoryFromTag(inventoryTag);
            List<PlayerInfoItem> equipment = parseArmorFromInventoryTag(inventoryTag);
            List<PlayerInfoItem> enderChest = parseItemListFromTag(saveTag, "EnderItems", 27);
            PlayerInfoItem mainHand = parseMainHandFromTag(saveTag, inventory);
            PlayerInfoItem offHand = parseOffHandFromInventoryTag(inventoryTag);
            return Optional.of(new PlayerInfoData(
                    playerName,
                    saveTag.contains("TimePlayed", 3) ? saveTag.getLong("TimePlayed") : 0L,
                    equipment,
                    mainHand,
                    offHand,
                    inventory,
                    enderChest
            ));
        } catch (Exception exception) {
            LOGGER.error("webcraft failed to load offline player snapshot for {}", playerName, exception);
            return Optional.empty();
        }
    }

    private record ProfileRecord(String name, String uuid) {
    }

    private static PlayerInfoData samplePlayer(String playerName) {
        PlayerInfoItem item = new PlayerInfoItem("minecraft:air", 0, 0, "", Map.of(), Map.of(), "item", "", "center", "contain", "no-repeat", "");
        String resolvedName = Objects.requireNonNullElse(playerName, "Unknown");
        return new PlayerInfoData(resolvedName, 1234L, List.of(item, item, item, item), item, item, List.of(item), List.of(item));
    }

    private static PlayerInfoData buildPlayerInfo(ServerPlayer player) {
        CompoundTag saveTag = loadPlayerSaveTag(player).orElseGet(() -> {
            CompoundTag fallback = new CompoundTag();
            player.addAdditionalSaveData(fallback);
            LOGGER.warn("webcraft player save fallback used for {}", player.getGameProfile().getName());
            return fallback;
        });

        ListTag inventoryTag = saveTag.getList("Inventory", 10);
        ListTag enderTag = saveTag.getList("EnderItems", 10);
        LOGGER.info(
                "webcraft player snapshot for {}: saveKeys={}, inventoryTagSize={}, enderTagSize={}, selectedSlot={}",
                player.getGameProfile().getName(),
                saveTag.getAllKeys(),
                inventoryTag.size(),
                enderTag.size(),
                saveTag.contains("SelectedItemSlot", 3) ? saveTag.getInt("SelectedItemSlot") : -1
        );
        List<PlayerInfoItem> inventory = parseInventoryFromTag(inventoryTag);
        List<PlayerInfoItem> equipment = parseArmorFromInventoryTag(inventoryTag);
        List<PlayerInfoItem> enderChest = parseItemListFromTag(saveTag, "EnderItems", 27);
        PlayerInfoItem mainHand = parseMainHandFromTag(saveTag, inventory);
        PlayerInfoItem offHand = parseOffHandFromInventoryTag(inventoryTag);

        return new PlayerInfoData(
                player.getGameProfile().getName(),
                player.tickCount,
                equipment,
                mainHand,
                offHand,
                inventory,
                enderChest
        );
    }

    private static List<PlayerInfoItem> parseItemListFromTag(CompoundTag saveTag, String key, int size) {
        List<PlayerInfoItem> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            items.add(emptyItem());
        }
        ListTag savedItems = saveTag.getList(key, 10);
        LOGGER.info("webcraft parsing {} entries for key {} (expected size {})", savedItems.size(), key, size);
        for (int i = 0; i < savedItems.size(); i++) {
            if (!(savedItems.get(i) instanceof CompoundTag compoundTag)) {
                continue;
            }
            int slot = compoundTag.contains("Slot", 99) ? compoundTag.getByte("Slot") : i;
            if (slot < 0 || slot >= items.size()) {
                continue;
            }
            items.set(slot, itemFromCompound(compoundTag));
        }
        return items;
    }

    private static List<PlayerInfoItem> parseInventoryFromTag(ListTag savedItems) {
        List<PlayerInfoItem> items = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            items.add(emptyItem());
        }
        LOGGER.info("webcraft parsing inventory entries: {}", savedItems.size());
        for (int i = 0; i < savedItems.size(); i++) {
            if (!(savedItems.get(i) instanceof CompoundTag compoundTag)) {
                continue;
            }
            int slot = compoundTag.contains("Slot", 99) ? compoundTag.getByte("Slot") : i;
            if (slot < 0 || slot >= items.size()) {
                continue;
            }
            items.set(slot, itemFromCompound(compoundTag));
        }
        return items;
    }

    private static List<PlayerInfoItem> parseArmorFromInventoryTag(ListTag inventoryTag) {
        PlayerInfoItem boots = emptyItem();
        PlayerInfoItem leggings = emptyItem();
        PlayerInfoItem chest = emptyItem();
        PlayerInfoItem helmet = emptyItem();
        for (int i = 0; i < inventoryTag.size(); i++) {
            if (!(inventoryTag.get(i) instanceof CompoundTag compoundTag)) {
                continue;
            }
            int slot = compoundTag.contains("Slot", 99) ? compoundTag.getByte("Slot") : i;
            if (slot == 100) {
                boots = itemFromCompound(compoundTag);
            } else if (slot == 101) {
                leggings = itemFromCompound(compoundTag);
            } else if (slot == 102) {
                chest = itemFromCompound(compoundTag);
            } else if (slot == 103) {
                helmet = itemFromCompound(compoundTag);
            }
        }
        return List.of(helmet, chest, leggings, boots);
    }

    private static PlayerInfoItem parseMainHandFromTag(CompoundTag saveTag, List<PlayerInfoItem> inventory) {
        int selectedSlot = saveTag.contains("SelectedItemSlot", 3) ? saveTag.getInt("SelectedItemSlot") : 0;
        LOGGER.info("webcraft selected main hand slot {}", selectedSlot);
        if (selectedSlot >= 0 && selectedSlot < inventory.size()) {
            PlayerInfoItem selected = inventory.get(selectedSlot);
            if (selected != null && !"minecraft:air".equals(selected.id())) {
                return selected;
            }
        }
        return emptyItem();
    }

    private static PlayerInfoItem parseOffHandFromInventoryTag(ListTag inventoryTag) {
        for (int i = 0; i < inventoryTag.size(); i++) {
            if (!(inventoryTag.get(i) instanceof CompoundTag compoundTag)) {
                continue;
            }
            int slot = compoundTag.contains("Slot", 99) ? compoundTag.getByte("Slot") : i;
            if (slot == -106) {
                return itemFromCompound(compoundTag);
            }
        }
        return emptyItem();
    }

    private static PlayerInfoItem itemFromCompound(CompoundTag compoundTag) {
        if (compoundTag == null || compoundTag.isEmpty()) {
            return emptyItem();
        }

        String id = compoundTag.getString("id");
        if (id == null || id.isBlank() || "minecraft:air".equals(id)) {
            return emptyItem();
        }
        int count = compoundTag.contains("count", 3) ? compoundTag.getInt("count") : 1;
        int durability = 0;
        if (compoundTag.contains("components", 10)) {
            CompoundTag components = compoundTag.getCompound("components");
            if (components.contains("minecraft:damage", 3)) {
                durability = components.getInt("minecraft:damage");
            }
        }
        String displayName = prettifyItemName(id);
        RenderMetadata metadata = resolveRenderMetadata(id);
        return new PlayerInfoItem(id, count, durability, displayName, Map.of(), metadata.modelDescriptor(), metadata.renderMode(), metadata.texturePath(), metadata.textureAnchor(), metadata.textureFit(), metadata.textureRepeat(), metadata.previewPath());
    }

    private static PlayerInfoItem emptyItem() {
        return new PlayerInfoItem("minecraft:air", 0, 0, "", Map.of(), Map.of(), "item", "", "center", "contain", "no-repeat", "");
    }

    private static RenderMetadata resolveRenderMetadata(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return missingnoRenderMetadata();
        }
        if ("minecraft:shield".equals(id)) {
            return new RenderMetadata("item", "/resources/textures/item/shield.png", "center", "contain", "no-repeat", "/resources/icon/item/shield.png", Map.of(
                    "kind", "special",
                    "stateKey", "default",
                    "variant", "default",
                    "model", "minecraft:item/shield",
                    "parent", "minecraft:item/shield",
                    "textures", Map.of("layer0", "minecraft:item/shield")
            ));
        }
        Item item = BuiltInRegistries.ITEM.get(location);
        if (item == null || item == BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("air"))) {
            return missingnoRenderMetadata();
        }
        if (item instanceof BlockItem && !FLAT_BLOCK_ITEM_OVERRIDES.contains(location.getPath())) {
            String path = "/resources/textures/block/" + location.getPath() + ".png";
            return new RenderMetadata("block", path, "center", "contain", "no-repeat", "/resources/previews/block/" + location.getPath() + ".png", Map.of(
                    "kind", "block",
                    "stateKey", "default",
                    "variant", "default",
                    "model", "minecraft:block/" + location.getPath(),
                    "parent", "minecraft:block/cube_all",
                    "textures", Map.of("all", "minecraft:block/" + location.getPath())
            ));
        }
        String path = "/resources/textures/item/" + location.getPath() + ".png";
        return new RenderMetadata("item", path, "center", "contain", "no-repeat", "/resources/previews/item/" + location.getPath() + ".png", Map.of(
                    "kind", "item",
                    "stateKey", "default",
                    "variant", "default",
                    "model", "minecraft:item/" + location.getPath(),
                    "parent", "minecraft:item/generated",
                    "textures", Map.of("layer0", "minecraft:item/" + location.getPath())
            ));
    }

    private static RenderMetadata missingnoRenderMetadata() {
        return new RenderMetadata("item", "/resources/textures/item/missingno.png", "center", "contain", "no-repeat", "/resources/previews/item/missingno.png", Map.of(
                "kind", "special",
                "stateKey", "default",
                "variant", "default",
                "model", MISSINGNO_ID,
                "parent", "minecraft:item/generated",
                "textures", Map.of("layer0", MISSINGNO_ID)
        ));
    }

    private record RenderMetadata(String renderMode, String texturePath, String textureAnchor, String textureFit, String textureRepeat, String previewPath, Map<String, Object> modelDescriptor) {
    }

    private static String prettifyItemName(String id) {
        String value = id == null ? "" : id;
        int colon = value.indexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        value = value.replace('_', ' ');
        if (value.isBlank()) {
            return "Item";
        }
        StringBuilder builder = new StringBuilder();
        boolean capitalize = true;
        for (char c : value.toCharArray()) {
            if (capitalize && Character.isLetter(c)) {
                builder.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                builder.append(c);
            }
            if (c == ' ') {
                capitalize = true;
            }
        }
        return builder.toString();
    }

    private static Optional<CompoundTag> loadPlayerSaveTag(ServerPlayer player) {
        try {
            Path playerFile = FMLPaths.GAMEDIR.get()
                    .resolve("world")
                    .resolve("playerdata")
                    .resolve(player.getUUID().toString() + ".dat");
            LOGGER.info("webcraft loading player save for {} from {}", player.getGameProfile().getName(), playerFile);
            if (!Files.exists(playerFile)) {
                LOGGER.warn("webcraft player save not found for {}", player.getGameProfile().getName());
                return Optional.empty();
            }
            CompoundTag tag = NbtIo.readCompressed(playerFile, NbtAccounter.unlimitedHeap());
            LOGGER.info("webcraft loaded player save for {} with keys {}", player.getGameProfile().getName(), tag.getAllKeys());
            return Optional.of(tag);
        } catch (Exception exception) {
            LOGGER.error("webcraft failed to load player save for {}", player.getGameProfile().getName(), exception);
            return Optional.empty();
        }
    }
}
