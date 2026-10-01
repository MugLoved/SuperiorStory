package com.mugloved.superiorstory.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.dialogue.QuestDef;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Datapack mini quests ({@code data/<ns>/superiorstory/quests/*.json}); the file path is the quest ID. */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class QuestDefinitions extends SimpleJsonResourceReloadListener {
    private static volatile Map<ResourceLocation, QuestDef> quests = Map.of();
    /** Entity lookups, built on first use because profiles load separately. */
    private static volatile Index index;

    public QuestDefinitions() {
        super(new Gson(), "superiorstory/quests");
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new QuestDefinitions());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, QuestDef> loaded = new LinkedHashMap<>();
        resources.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> {
                if (!com.mugloved.superiorstory.dialogue.Dialogue.modsLoaded(entry.getValue(), net.minecraftforge.fml.ModList.get()::isLoaded)) return;
                try {
                    loaded.put(entry.getKey(), QuestDef.parse(entry.getKey(), entry.getValue()));
                } catch (RuntimeException exception) {
                    SuperiorStory.LOGGER.error("Invalid quest {}: {}", entry.getKey(), exception.getMessage());
                }
            });
        quests = Map.copyOf(loaded);
        index = null;
        SuperiorStory.LOGGER.info("Loaded {} quest(s) from datapacks", quests.size());
    }

    /**
     * Warns for each quest whose entity has no death loot table: the drop modifier cannot add to loot that never rolls (some
     * bosses spawn chests or drop items in code). Runs once when datapacks finish loading.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;   // only the full sync at server start and after /reload
        var lootData = event.getPlayerList().getServer().getLootData();
        for (QuestDef quest : quests.values()) {
            ResourceLocation entity = entityOf(quest);
            if (quest.fixedItem() == null || entity == null) continue;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(entity).orElse(null);
            if (type == null) {
                SuperiorStory.LOGGER.warn("Quest {} names unknown entity {}", quest.id(), entity);
            } else if (type.getDefaultLootTable() == BuiltInLootTables.EMPTY || lootData.getLootTable(type.getDefaultLootTable()) == LootTable.EMPTY) {
                SuperiorStory.LOGGER.warn("Quest {}: {} has no death loot table, so its quest item cannot drop", quest.id(), entity);
            }
        }
    }

    /** Called when structure profiles reload: a quest's default entity comes from its profile. */
    static void invalidate() {
        index = null;
    }

    @Nullable
    public static QuestDef get(ResourceLocation id) {
        return quests.get(id);
    }

    public static Collection<QuestDef> all() {
        return quests.values();
    }

    /** The entity whose death counts for the quest: the authored one, else the structure profile's first boss. */
    @Nullable
    public static ResourceLocation entityOf(QuestDef quest) {
        if (quest.entity() != null) return quest.entity();
        ResourceLocation exact = quest.location() == null ? null : quest.location().exactStructure();
        if (exact == null) return null;
        List<ResourceLocation> bosses = StructureProfiles.bossesOf(exact);
        return bosses.isEmpty() ? null : bosses.get(0);
    }

    /** The entity whose death completes an extra objective: the authored one, else the structure profile's first boss. */
    @Nullable
    public static ResourceLocation bossOf(QuestDef.Objective objective) {
        if (objective.entity() != null) return objective.entity();
        ResourceLocation exact = objective.location().exactStructure();
        if (objective.deliver() || exact == null) return null;
        List<ResourceLocation> bosses = StructureProfiles.bossesOf(exact);
        return bosses.isEmpty() ? null : bosses.get(0);
    }

    /** Quests with an item that this entity type drops when it dies. */
    public static List<QuestDef> dropsFor(ResourceLocation entityId) {
        return index().drops().getOrDefault(entityId, List.of());
    }

    /** Every entity type some quest names. */
    public static Set<ResourceLocation> entityIds() {
        return index().entities();
    }

    private record Index(Map<ResourceLocation, List<QuestDef>> drops, Set<ResourceLocation> entities) {}

    private static Index index() {
        Index current = index;
        if (current == null) {
            Map<ResourceLocation, List<QuestDef>> map = new HashMap<>();
            Set<ResourceLocation> entities = new HashSet<>();
            for (QuestDef quest : quests.values()) {
                for (QuestDef.Objective objective : quest.extra()) {
                    ResourceLocation extra = bossOf(objective);
                    if (extra != null) entities.add(extra);
                }
                ResourceLocation entity = entityOf(quest);
                if (entity == null) continue;
                entities.add(entity);
                if (quest.fixedItem() != null) map.computeIfAbsent(entity, key -> new ArrayList<>()).add(quest);
            }
            current = new Index(map, Set.copyOf(entities));
            index = current;
        }
        return current;
    }
}
