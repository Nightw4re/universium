package com.universium.webcraft.quests;

import dev.ftb.mods.ftblibrary.icon.IResourceIcon;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.reward.RandomReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.XPLevelsReward;
import dev.ftb.mods.ftbquests.quest.reward.XPReward;
import dev.ftb.mods.ftbquests.quest.task.AdvancementTask;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.KillTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Uses only the currently loaded FTB world data. All calls run on the server thread. */
final class FtbQuestReader {
    private static final UUID ANONYMOUS = new UUID(0, 0);
    private static final int MAX_QUESTS = 10_000;
    private static final int MAX_CHAPTERS = 1_024;
    private static final int MAX_TEXT_CHARS = 1_000_000;
    private static final int MAX_DETAILS = 256;
    private static final int MAX_CATALOG_DETAILS = 50_000;
    private static final ResourceLocation RANDOM_REWARD_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ftblibrary", "textures/icons/dice.png");

    private FtbQuestReader() {}

    static QuestDataRoute.Capture capture(MinecraftServer server, UUID player, boolean progressOnly) {
        var file = ServerQuestFile.getInstance().orElse(null);
        if (file == null || file.server != server || file.isLoading()) return QuestDataRoute.Failure.NOT_READY;

        TeamData selected = null;
        if (player != null) {
            var teams = FTBTeamsAPI.api();
            if (!teams.isManagerLoaded()) return QuestDataRoute.Failure.NOT_READY;
            var manager = teams.getManager();
            if (manager.getServer() != server) return QuestDataRoute.Failure.NOT_READY;
            var team = manager.getTeamForPlayerID(player).orElse(null);
            if (team == null) return QuestDataRoute.Failure.UNKNOWN_PLAYER;
            // FTB Quests indexes progress by the effective team's getId(), including party teams.
            selected = file.getNullableTeamData(team.getId());
            if (selected == null) return QuestDataRoute.Failure.NO_TEAM_PROGRESS;
        }

        // A new detached TeamData starts empty. Its visibility caches stay out of the world.
        var anonymous = new TeamData(ANONYMOUS, file);
        var registries = server.registryAccess();
        var items = new QuestItems(registries);
        var chapters = new ArrayList<QuestDataRoute.Chapter>();
        int questCount = 0;
        int textChars = 0;
        int detailCount = 0;
        for (var chapter : file.getVisibleChapters(anonymous)) {
            if (chapter.isAlwaysInvisible()) continue;
            var quests = new ArrayList<QuestDataRoute.Quest>();
            for (var quest : chapter.getQuests()) {
                if (!quest.isVisible(anonymous) || (quest.hideDetailsUntilStartable() && !anonymous.canStartTasks(quest))) {
                    continue;
                }
                if (++questCount > MAX_QUESTS) return QuestDataRoute.Failure.TOO_LARGE;
                var completed = selected != null && selected.isCompleted(quest);
                var completedAt = completed ? selected.getCompletedTime(quest.getId()).map(java.util.Date::getTime).orElse(null) : null;
                if (progressOnly) {
                    quests.add(new QuestDataRoute.Quest(quest.getCodeString(), "", "", completed, null, completedAt,
                            null, List.of(), List.of()));
                    continue;
                }
                String description = quest.getHideTextUntilComplete().get(chapter.isHideTextUntilComplete())
                        ? "" : QuestText.description(quest);
                int remaining = MAX_CATALOG_DETAILS - detailCount;
                var tasks = quest.getTasks().stream().filter(task -> !task.isOptionalForProgression(anonymous))
                        .limit(Math.min(MAX_DETAILS, remaining)).map(task -> task(task, server, items)).toList();
                var rewards = quest.getRewards().stream().limit(Math.min(MAX_DETAILS, remaining - tasks.size()))
                        .map(reward -> reward(reward, registries, items)).toList();
                detailCount += tasks.size() + rewards.size();
                String title = QuestText.title(quest);
                if (quest.getRawTitle().isEmpty() && !quest.getTasksAsList().isEmpty()
                        && quest.getTasksAsList().getFirst() instanceof ItemTask first) {
                    title = tasks.stream().filter(detail -> detail.id().equals(first.getCodeString()))
                            .findFirst().orElseGet(() -> task(first, server, items)).title();
                }
                textChars += title.length() + description.length();
                if (textChars > MAX_TEXT_CHARS) return QuestDataRoute.Failure.TOO_LARGE;
                var fallback = tasks.stream().map(QuestDataRoute.Detail::art).filter(Objects::nonNull).findFirst().orElse(null);
                if (fallback == null && !quest.getTasksAsList().isEmpty()) {
                    fallback = quest.getTasksAsList().stream().limit(MAX_DETAILS)
                            .filter(task -> task.isOptionalForProgression(anonymous))
                            .map(task -> task(task, server, items).art()).filter(Objects::nonNull).findFirst().orElse(null);
                }
                var icon = withFallback(configuredArt(quest, registries, items), fallback);
                var layout = new QuestDataRoute.Layout(quest.getX(), quest.getY(), quest.getSize(), quest.getShape(),
                        quest.streamDependencies().map(dependency -> dependency.getCodeString()).distinct().toList(),
                        quest.getMinRequiredDependencies(), quest.shouldHideDependencyLines(), quest.shouldHideDependentLines());
                quests.add(new QuestDataRoute.Quest(quest.getCodeString(), title, description, completed, icon, completedAt,
                        layout, tasks, rewards));
            }
            if (quests.isEmpty()) continue;
            if (chapters.size() >= MAX_CHAPTERS) return QuestDataRoute.Failure.TOO_LARGE;
            if (progressOnly) {
                chapters.add(new QuestDataRoute.Chapter(chapter.getCodeString(), "", List.copyOf(quests)));
                continue;
            }
            String title = QuestText.title(chapter);
            textChars += title.length();
            if (textChars > MAX_TEXT_CHARS) return QuestDataRoute.Failure.TOO_LARGE;
            var group = chapter.getGroup();
            var groupDetail = group.isDefaultGroup() ? null : new QuestDataRoute.Detail(group.getCodeString(),
                    QuestText.title(group), 0, configuredArt(group, registries, items));
            var images = chapter.getImages().stream().filter(image -> image.shouldShowImage(anonymous))
                    .limit(MAX_DETAILS).sorted(Comparator.comparingInt(ChapterImage::getOrder))
                    .map(image -> decoration(image, items)).filter(Objects::nonNull).toList();
            chapters.add(new QuestDataRoute.Chapter(chapter.getCodeString(), title, List.copyOf(quests),
                    configuredArt(chapter, registries, items), groupDetail, images));
        }
        return new QuestDataRoute.Catalog(List.copyOf(chapters));
    }

    private static QuestDataRoute.Art configuredArt(QuestObjectBase object, HolderLookup.Provider registries, QuestItems items) {
        // The configured icon is a stack. The custom icon item stores the texture reference as a component.
        var data = new CompoundTag();
        object.writeData(data, registries);
        return configuredArt(data, registries, items);
    }

    private static QuestDataRoute.Art configuredArt(CompoundTag data, HolderLookup.Provider registries, QuestItems items) {
        var icon = data.getCompound("icon");
        String itemId = icon.getString("id");
        if (itemId.equals("ftbquests:custom_icon")) {
            return textureArt(icon.getCompound("components").getString("ftbquests:icon"), items);
        }
        return itemArt(items.representative(ItemStack.parseOptional(registries, icon)));
    }

    private static QuestDataRoute.Art textureArt(String value, QuestItems items) {
        if (value.startsWith("item:")) {
            var id = ResourceLocation.tryParse(value.substring(5));
            return id == null ? null : BuiltInRegistries.ITEM.getOptional(id)
                    .map(item -> itemArt(items.representative(item.getDefaultInstance()))).orElse(null);
        }
        var texture = QuestArtwork.texture(value);
        if (texture == null) return null;
        String path = texture.getPath();
        String itemId = null;
        if (path.startsWith("textures/item/") || path.startsWith("textures/block/")) {
            var id = texture.withPath(path.substring(path.indexOf('/', "textures/".length()) + 1, path.length() - ".png".length()));
            var item = BuiltInRegistries.ITEM.getOptional(id);
            if (item.isPresent()) {
                var candidate = items.representative(item.get().getDefaultInstance());
                if (!candidate.isEmpty()) itemId = QuestItems.id(candidate);
            }
        }
        return new QuestDataRoute.Art(itemId, texture);
    }

    private static QuestDataRoute.Art itemArt(ItemStack stack) {
        return stack.isEmpty() ? null : new QuestDataRoute.Art(QuestItems.id(stack), null);
    }

    private static QuestDataRoute.Art withFallback(QuestDataRoute.Art configured, QuestDataRoute.Art fallback) {
        if (configured == null) return fallback;
        return fallback == null ? configured : new QuestDataRoute.Art(configured.itemId(), configured.texture(), fallback);
    }

    private static QuestDataRoute.Detail task(Task task, MinecraftServer server, QuestItems items) {
        var registries = server.registryAccess();
        var data = new CompoundTag();
        task.writeData(data, registries);
        var icon = configuredArt(data, registries, items);
        var type = task.getType().getTypeId();
        if (task instanceof ItemTask item) {
            var representative = items.representative(item.getItemStack());
            String hint = representative.isEmpty() ? items.filterHint(item.getItemStack()).orElse(null) : null;
            return itemDetail(item, representative, hint, type, task.hideProgressNumbers() ? 0 :
                    Math.min(task.getMaxProgress(), 9_007_199_254_740_991L), 0, icon);
        }
        if (task instanceof KillTask) {
            var target = killTarget(data, registries);
            var egg = target == null ? null : SpawnEggItem.byId(target);
            String title = !task.getRawTitle().isEmpty() ? QuestText.title(task) : killTitle(data, target, registries);
            var fallback = itemArt((egg != null ? egg : Items.SPAWNER).getDefaultInstance());
            if (target == EntityType.ENDER_DRAGON) {
                fallback = withFallback(textureArt("minecraft:textures/entity_icon/enderdragon/dragon.png", items),
                        itemArt(Items.DRAGON_HEAD.getDefaultInstance()));
            }
            var art = withFallback(icon, fallback);
            return new QuestDataRoute.Detail(task.getCodeString(), title,
                    task.hideProgressNumbers() ? 0 : Math.min(task.getMaxProgress(), 9_007_199_254_740_991L),
                    art, true, null, 0);
        }
        if (task instanceof AdvancementTask && icon == null) {
            var id = ResourceLocation.tryParse(data.getString("advancement"));
            var holder = id == null ? null : server.getAdvancements().get(id);
            if (holder != null) icon = holder.value().display()
                    .map(display -> itemArt(items.representative(display.getIcon()))).orElse(null);
        }
        String title = !task.getRawTitle().isEmpty() || standardTargetTask(type)
                ? QuestText.title(task) : typeTitle(type);
        if (title.startsWith("ftbquests.task.")) {
            title = title.replaceFirst("^ftbquests\\.task\\.[a-z0-9_.]+", typeTitle(type));
        }
        return new QuestDataRoute.Detail(task.getCodeString(), title,
                task.hideProgressNumbers() || type.getPath().equals("fluid") ? 0
                        : Math.min(task.getMaxProgress(), 9_007_199_254_740_991L), icon);
    }

    private static EntityType<?> killTarget(CompoundTag data, HolderLookup.Provider registries) {
        String tag = data.getString("entityTypeTag");
        if (!tag.isEmpty()) {
            var tagId = ResourceLocation.tryParse(tag);
            if (tagId == null) return null;
            var members = registries.lookupOrThrow(Registries.ENTITY_TYPE).get(TagKey.create(Registries.ENTITY_TYPE, tagId));
            return members.flatMap(value -> value.stream().limit(MAX_DETAILS).map(holder -> holder.value())
                    .filter(type -> SpawnEggItem.byId(type) != null).findFirst()).orElse(null);
        }
        var id = ResourceLocation.tryParse(data.getString("entity"));
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    private static String killTitle(CompoundTag data, EntityType<?> target, HolderLookup.Provider registries) {
        String tag = data.getString("entityTypeTag");
        String targetName;
        if (!tag.isEmpty()) targetName = "#" + tag;
        else if (target != null) targetName = QuestText.plain(target.getDescription(), QuestText.MAX_TITLE_CHARS);
        else targetName = data.getString("entity");
        String name = QuestText.plain(data.getString("custom_name"), registries, QuestText.MAX_TITLE_CHARS);
        String title = !name.isBlank() ? "Kill: " + name + " (" + targetName + ')'
                : targetName.isEmpty() ? "Kill" : "Kill: " + targetName;
        return QuestText.plain(Component.literal(title), QuestText.MAX_TITLE_CHARS);
    }

    private static boolean standardTargetTask(ResourceLocation type) {
        return type.getNamespace().equals("ftbquests") && switch (type.getPath()) {
            case "advancement", "biome", "dimension", "fluid", "structure" -> true;
            default -> false;
        };
    }

    private static QuestDataRoute.Detail reward(Reward reward, HolderLookup.Provider registries, QuestItems items) {
        var data = new CompoundTag();
        reward.writeData(data, registries);
        var icon = configuredArt(data, registries, items);
        if (reward instanceof ItemReward item) {
            long countMax = (long) item.getCount() + Math.max(0, data.getInt("random_bonus"));
            var representative = items.representative(item.getItem());
            String hint = representative.isEmpty() ? items.filterHint(item.getItem()).orElse(null) : null;
            return itemDetail(item, representative, hint, reward.getType().getTypeId(),
                    item.getCount(), countMax, icon);
        }
        // A command reward's generated title includes the command itself. Publish only its display type or the configured title.
        String title = reward.getRawTitle().isEmpty() ? typeTitle(reward.getType().getTypeId()) : QuestText.title(reward);
        if (reward instanceof RandomReward random) {
            var table = random.getTable();
            var dice = new QuestDataRoute.Art(null, RANDOM_REWARD_TEXTURE);
            var tableArt = table == null ? null : configuredArt(table, registries, items);
            if (table != null && reward.getRawTitle().isEmpty()) {
                var name = QuestText.plain(table.getRawTitle(), registries, QuestText.MAX_TITLE_CHARS);
                if (!name.isBlank() && !name.equals("ftbquests.reward_table")) title = name;
            }
            return new QuestDataRoute.Detail(reward.getCodeString(), title, 0,
                    withFallback(icon, withFallback(tableArt, dice)), true, null, 0);
        }
        if (reward instanceof XPReward || reward instanceof XPLevelsReward) {
            int count = data.getInt(reward instanceof XPReward ? "xp" : "xp_levels");
            var art = withFallback(icon, itemArt(Items.EXPERIENCE_BOTTLE.getDefaultInstance()));
            return new QuestDataRoute.Detail(reward.getCodeString(), title, count, art, true, null, 0);
        }
        return new QuestDataRoute.Detail(reward.getCodeString(), title, 0, icon);
    }

    private static QuestDataRoute.Detail itemDetail(QuestObjectBase object, ItemStack item, String filterHint, ResourceLocation type,
                                                    long count, long countMax, QuestDataRoute.Art configured) {
        String itemId = item.isEmpty() ? null : QuestItems.id(item);
        String title = !object.getRawTitle().isEmpty() ? QuestText.title(object)
                : !item.isEmpty() ? QuestText.plain(item.getHoverName(), QuestText.MAX_TITLE_CHARS)
                : filterHint == null ? typeTitle(type) : filterHint;
        return new QuestDataRoute.Detail(object.getCodeString(), title, count,
                withFallback(configured, itemArt(item)), true, itemId, countMax);
    }

    private static String typeTitle(ResourceLocation type) {
        String name = type.getPath().replace('_', ' ');
        if (name.equals("xp")) return "XP";
        if (name.equals("xp levels")) return "XP levels";
        return name.isEmpty() ? type.toString() : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private static QuestDataRoute.Art iconArt(Icon icon, QuestItems items) {
        return switch (icon) {
            case ItemIcon item -> itemArt(items.representative(item.getStack()));
            case IResourceIcon resource -> {
                var location = resource.getResourceLocation();
                yield location == null ? null : textureArt(location.toString(), items);
            }
            case null, default -> null;
        };
    }

    private static QuestDataRoute.Decoration decoration(ChapterImage image, QuestItems items) {
        var art = iconArt(image.getImage(), items);
        if (art == null || !Double.isFinite(image.getX()) || !Double.isFinite(image.getY())
                || !Double.isFinite(image.getWidth()) || !Double.isFinite(image.getHeight())
                || !Double.isFinite(image.getRotation())) return null;
        double x = image.getX();
        double y = image.getY();
        if (image.isAlignToCorner() && image.getRotation() != 0) {
            // Preserve the top-left pivot in a response that positions images by center.
            double angle = Math.toRadians(image.getRotation());
            double halfWidth = image.getWidth() / 2;
            double halfHeight = image.getHeight() / 2;
            x += halfWidth * (Math.cos(angle) - 1) - halfHeight * Math.sin(angle);
            y += halfWidth * Math.sin(angle) + halfHeight * (Math.cos(angle) - 1);
        }
        return new QuestDataRoute.Decoration(image.getCodeString(), art, x, y, image.getWidth(), image.getHeight(),
                image.getRotation(), Math.clamp(image.getAlpha(), 0, 255), false);
    }
}
