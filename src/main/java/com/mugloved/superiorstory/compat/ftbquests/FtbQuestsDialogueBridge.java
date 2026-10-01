package com.mugloved.superiorstory.compat.ftbquests;

import com.google.gson.JsonElement;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryHooks;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.util.ProgressChange;
import net.minecraft.server.level.ServerPlayer;

/**
 * FTB Quests keys for dialogue JSON, loaded only when FTB Quests is present:
 * <ul>
 *   <li>{@code "ftb_quest": "<id>"} / {@code "ftb_task": "<id>"} on a choice completes that quest or task for the player's
 *       team (use FTB Quests' Copy ID; a Custom task is the natural target).</li>
 *   <li>{@code "if": {"ftb_quest": "<id>"}} / {@code "unless": ...} tests whether it is already completed.</li>
 * </ul>
 */
public final class FtbQuestsDialogueBridge {
    private FtbQuestsDialogueBridge() {}

    public static void register() {
        StoryHooks.registerAction("ftb_quest", value -> {
            String code = code(value);
            return context -> complete(context.player(), code);
        });
        StoryHooks.registerAction("ftb_task", value -> {
            String code = code(value);
            return context -> complete(context.player(), code);
        });
        StoryHooks.registerCondition("ftb_quest", value -> {
            String code = code(value);
            return context -> completed(context.player(), code);
        });
    }

    private static String code(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("An FTB Quests ID must be a string");
        return value.getAsString();
    }

    private static QuestObject find(String code) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null) return null;
        try {
            QuestObject object = file.get(QuestObjectBase.parseCodeString(code));
            if (object == null) SuperiorStory.LOGGER.warn("Dialogue references unknown FTB Quests object {}", code);
            return object;
        } catch (RuntimeException exception) {
            SuperiorStory.LOGGER.warn("Dialogue references invalid FTB Quests ID {}", code);
            return null;
        }
    }

    private static void complete(ServerPlayer player, String code) {
        QuestObject object = find(code);
        if (object == null) return;
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        TeamData team = file.getOrCreateTeamData(player);
        object.forceProgress(team, new ProgressChange(file, object, player.getUUID()));
    }

    private static boolean completed(ServerPlayer player, String code) {
        QuestObject object = find(code);
        return object != null && ServerQuestFile.INSTANCE.getOrCreateTeamData(player).isCompleted(object);
    }
}
