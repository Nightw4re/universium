package com.universium.webcraft.quests;

import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Uses only the currently loaded FTB world data. All calls run on the server thread. */
final class FtbQuestReader {
    private static final UUID ANONYMOUS = new UUID(0, 0);
    private static final int MAX_QUESTS = 10_000;
    private static final int MAX_CHAPTERS = 1_024;
    private static final int MAX_TEXT_CHARS = 1_000_000;

    private FtbQuestReader() {}

    static QuestDataRoute.Capture capture(MinecraftServer server, UUID player) {
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
        var chapters = new ArrayList<QuestDataRoute.Chapter>();
        int questCount = 0;
        int textChars = 0;
        for (var chapter : file.getVisibleChapters(anonymous)) {
            if (chapter.isAlwaysInvisible()) continue;
            var quests = new ArrayList<QuestDataRoute.Quest>();
            for (var quest : chapter.getQuests()) {
                if (!quest.isVisible(anonymous) || (quest.hideDetailsUntilStartable() && !anonymous.canStartTasks(quest))) {
                    continue;
                }
                if (++questCount > MAX_QUESTS) return QuestDataRoute.Failure.TOO_LARGE;
                String title = QuestText.title(quest);
                String description = quest.getHideTextUntilComplete().get(chapter.isHideTextUntilComplete())
                        ? "" : QuestText.description(quest);
                textChars += title.length() + description.length();
                if (textChars > MAX_TEXT_CHARS) return QuestDataRoute.Failure.TOO_LARGE;
                quests.add(new QuestDataRoute.Quest(quest.getCodeString(), title, description,
                        selected != null && selected.isCompleted(quest)));
            }
            if (quests.isEmpty()) continue;
            String title = QuestText.title(chapter);
            textChars += title.length();
            if (chapters.size() >= MAX_CHAPTERS || textChars > MAX_TEXT_CHARS) {
                return QuestDataRoute.Failure.TOO_LARGE;
            }
            chapters.add(new QuestDataRoute.Chapter(chapter.getCodeString(), title, List.copyOf(quests)));
        }
        return new QuestDataRoute.Catalog(List.copyOf(chapters));
    }
}
