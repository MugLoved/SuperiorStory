package com.mugloved.superiorstory;

import com.google.gson.JsonParser;
import com.mugloved.superiorstory.dialogue.Dialogue;
import com.mugloved.superiorstory.dialogue.Duration;
import com.mugloved.superiorstory.dialogue.IdSelector;
import com.mugloved.superiorstory.dialogue.ItemSpec;
import com.mugloved.superiorstory.dialogue.LocateSpec;
import com.mugloved.superiorstory.dialogue.QuestDef;
import com.mugloved.superiorstory.dialogue.Vars;
import com.mugloved.superiorstory.server.StructurePools;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The contracts of the modular authoring system: one registry, derived defaults, load-time validation. */
class ModularAuthoringTest {
    private static final ResourceLocation ID = new ResourceLocation("superiorstory", "test");

    @BeforeAll
    static void registerModules() {
        TestHooks.ensure();
    }

    private static Dialogue parse(String json) {
        return Dialogue.parse(ID, JsonParser.parseString(json));
    }

    private static ResourceLocation rl(String id) {
        return new ResourceLocation(id);
    }

    // ---------------------------------------------------------------- registry

    @Test
    void rewardPoolsAndTheRewardChoiceActionAreValidatedAtLoad() {
        assertNotNull(com.mugloved.superiorstory.api.StoryHooks.action("reward_choice").apply(JsonParser.parseString(
            "{\"pool\":\"superiorstory:p\",\"count\":4,\"pick\":1,\"fresh\":true}")));
        assertThrows(IllegalArgumentException.class, () -> com.mugloved.superiorstory.api.StoryHooks.action("reward_choice").apply(JsonParser.parseString(
            "{\"pool\":\"superiorstory:p\",\"fresh\":\"yes\"}")));
        var pool = com.mugloved.superiorstory.dialogue.RewardPool.parse(JsonParser.parseString(
            "{\"rewards\":[{\"give\":{\"item\":\"minecraft:diamond\",\"quantity\":3},\"weight\":2},{\"give\":\"minecraft:emerald\",\"text\":\"Emerald\"}]}"));
        assertEquals(2, pool.entries().size());
        assertEquals(2, pool.entries().get(0).weight());
        assertEquals("@item:minecraft:diamond", pool.entries().get(0).label());
        assertEquals("Emerald", pool.entries().get(1).label());
        assertThrows(IllegalArgumentException.class, () -> com.mugloved.superiorstory.dialogue.RewardPool.parse(JsonParser.parseString("{\"rewards\":[{\"weight\":1}]}")));
        assertThrows(IllegalArgumentException.class, () -> com.mugloved.superiorstory.dialogue.RewardPool.parse(JsonParser.parseString("{\"rewards\":[{\"give\":\"a:b\",\"wieght\":1}]}")));
        assertNotNull(QuestDef.parse(rl("superiorstory:t"), JsonParser.parseString(
            "{\"location\":{\"structure\":\"cataclysm:burning_arena\"},\"entity\":\"cataclysm:ignis\",\"item\":\"minecraft:stick\",\"reward\":{\"reward_choice\":{\"pool\":\"superiorstory:p\",\"count\":3,\"pick\":2}}}")));
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(rl("superiorstory:t"), JsonParser.parseString(
            "{\"location\":{\"structure\":\"cataclysm:burning_arena\"},\"entity\":\"cataclysm:ignis\",\"item\":\"minecraft:stick\",\"reward\":{\"reward_choice\":{\"pool\":\"superiorstory:p\",\"count\":2,\"pick\":3}}}")));
    }

    @Test
    void theBountyLineTakesAnEmptyObjectAndRejectsUnknownFields() {
        assertNotNull(parse("{\"npc\":\"minecraft:villager\",\"beats\":[{\"text\":\"A\",\"bounty\":{}}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"bounty\":{\"decree\":\"farmer\"}}]}"));
    }

    @Test
    void anUnknownKeyFailsOnlyItsOwnFile() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"no_such_module\":1}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"if\":{\"no_such_condition\":1}}]}"));
        Dialogue sibling = parse("{\"npc\":\"x\",\"beats\":[\"Still fine.\"]}");   // a valid sibling keeps loading
        assertEquals(1, sibling.branches().get("main").size());
    }

    @Test
    void conditionsWorkAtDialogueLineAndChoiceLevelWithTimeAndDimension() {
        Dialogue dialogue = parse("""
            {"npc":"x","if":{"dimension":"minecraft:the_nether"},"beats":[
              {"text":"Late.","if":{"time":"night"}},
              {"text":"Pick.","choices":[{"text":"Here","if":{"dimension":["minecraft:overworld","mymod:*"]}}]}]}""");
        assertTrue(dialogue.conditional());
        assertFalse(dialogue.branches().get("main").get(0).guard().isEmpty());
        assertFalse(dialogue.branches().get("main").get(1).choices().get(0).guard().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"if\":{\"time\":\"dusk\"}}]}"));
    }

    @Test
    void speakersAndTriggersBindTheDialogueAndScenesStayScenes() {
        assertEquals("block", parse("{\"block\":\"cataclysm:altar_of_fire\",\"beats\":[\"Cold.\"]}").speakerKey());
        assertEquals("command", parse("{\"trigger\":\"command\",\"beats\":[\"Hello.\"]}").triggerKey());
        Dialogue quest = parse("{\"trigger\":{\"quest\":\"superiorstory:q\",\"stage\":\"collected\"},\"beats\":[\"Warm.\"]}");
        assertEquals("quest", quest.triggerKey());
        Dialogue ended = parse("{\"trigger\":{\"dialogue_end\":\"a:b\",\"outcome\":\"good\"},\"beats\":[\"Thanks.\"]}");
        assertEquals("dialogue_end", ended.triggerKey());
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"block\":\"a:b\",\"beats\":[\"A\"]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"trigger\":{\"nothing\":1},\"beats\":[\"A\"]}"));
        assertFalse(Dialogue.isDialogue(JsonParser.parseString("{\"trigger\":\"first_join\",\"beats\":[\"A\"]}")));
        assertFalse(Dialogue.isDialogue(JsonParser.parseString("{\"beats\":[\"A\"]}")));
    }

    @Test
    void handOverAndUseBlockCompileAndBadValuesAreRejected() {
        Dialogue dialogue = parse("""
            {"block":"a:b","beats":[{"text":"Give","choices":[
              {"text":"One","hand_over":"cataclysm:burning_ashes"},
              {"text":"Three","hand_over":{"item":"minecraft:blaze_rod","quantity":3}},
              {"text":"Key","hand_over":{"item":"minecraft:trial_key","consume":false}},
              {"text":"Quest","hand_over":{"quest":"superiorstory:ignis_ashes"}}
              ]}]}""");
        assertEquals(4, dialogue.branches().get("main").get(0).choices().size());
        assertThrows(IllegalArgumentException.class, () -> parse(
            "{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[{\"text\":\"B\",\"hand_over\":{\"item\":\"a:b\",\"quantity\":0}}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[{\"text\":\"B\",\"use_block\":{\"typo\":1}}]}]}"));
    }

    @Test
    void locateTargetsAreOneOfPoolStructureGeneralOrQuest() {
        LocateSpec pool = spec("{\"pool\":[\"superiorstory:boss_lairs\",\"superiorstory:cataclysm\"]}");
        assertEquals(LocateSpec.Mode.POOL, pool.mode());
        assertEquals(2, pool.pools().size());
        assertEquals(LocateSpec.Mode.STRUCTURE, spec("{\"structure\":\"cataclysm:burning_arena\"}").mode());
        assertEquals(LocateSpec.Mode.GENERAL, spec("{\"general\":true}").mode());
        assertEquals(LocateSpec.Mode.QUEST, spec("{\"quest\":\"superiorstory:ignis_ashes\"}").mode());
        assertEquals(LocateSpec.Mode.DEFAULT, spec("{}").mode());
        assertThrows(IllegalArgumentException.class, () -> spec("{\"pool\":\"a:b\",\"general\":true}"));
        assertThrows(IllegalArgumentException.class, () -> spec("{\"only\":\"a:b\"}"));   // renamed to structure
    }

    private static LocateSpec spec(String json) {
        return LocateSpec.parse(JsonParser.parseString(json), "test");
    }

    // ---------------------------------------------------------------- primitives

    @Test
    void durationsParseInRealTimeUnitsAndFormatTheTwoLargest() {
        assertEquals(45_000L, Duration.parse("45s"));
        assertEquals(1_800_000L, Duration.parse("30m"));
        assertEquals(5_400_000L, Duration.parse("1h30m"));
        assertEquals(86_400_000L, Duration.parse("1d"));
        assertThrows(IllegalArgumentException.class, () -> Duration.parse("1 hour"));
        assertThrows(IllegalArgumentException.class, () -> Duration.parse("0s"));
        assertThrows(IllegalArgumentException.class, () -> Duration.parse("100 ticks"));
        assertEquals("1d 4h", Duration.format(28L * 3_600_000L + 200, u -> u));
        assertEquals("2h 14m", Duration.format((2 * 3600 + 14 * 60 + 30) * 1000L, u -> u));
        assertEquals("45s", Duration.format(44_100L, u -> u));
        assertEquals("1h", Duration.format(3_600_000L, u -> u));
    }

    @Test
    void itemSpecsTakeShortAndObjectFormsWithDefaults() {
        ItemSpec shortForm = ItemSpec.parse(JsonParser.parseString("\"cataclysm:burning_ashes\""));
        assertEquals(1, shortForm.quantity());
        assertTrue(shortForm.consume());
        assertEquals(IdSelector.Kind.EXACT, shortForm.item().kind());
        ItemSpec tag = ItemSpec.parse(JsonParser.parseString("\"#forge:ingots/iron\""));
        assertEquals(IdSelector.Kind.TAG, tag.item().kind());
        ItemSpec full = ItemSpec.parse(JsonParser.parseString("{\"item\":\"minecraft:blaze_rod\",\"quantity\":3,\"consume\":false}"));
        assertEquals(3, full.quantity());
        assertFalse(full.consume());
        assertEquals(2, ItemSpec.parseAll(JsonParser.parseString("[\"a:b\",{\"item\":\"c:d\",\"quantity\":2}]")).size());
        assertThrows(IllegalArgumentException.class, () -> ItemSpec.parse(JsonParser.parseString("{\"quantity\":2}")));
    }

    @Test
    void variablesFillInlineItemAndEntityReferencesAndPrefixedValues() {
        Vars.Resolver resolver = (kind, ref) -> kind + ":" + ref;
        Vars.Filled filled = Vars.fill("Bring {item:cataclysm:burning_ashes} from {entity:cataclysm:ignis} in {cooldown}.",
            Map.of("cooldown", Vars.TIME + "5000"), resolver);
        assertEquals("Bring item:cataclysm:burning_ashes from entity:cataclysm:ignis in time:5000.", filled.text());
        assertEquals("item", filled.spans().get(0).kind());
        assertEquals("cataclysm:burning_ashes", filled.spans().get(0).ref());
        assertEquals("entity", filled.spans().get(1).kind());
        assertEquals("time", filled.spans().get(2).kind());
        assertEquals("Take item:a:b", Vars.fill("Take {reward}", Map.of("reward", Vars.ITEM + "a:b"), resolver).text());
    }

    // ---------------------------------------------------------------- quests and pools

    @Test
    void aMiniQuestDerivesItsObjectiveAndKeepsEverythingElseDefaulted() {
        QuestDef quest = QuestDef.parse(ID, JsonParser.parseString(
            "{\"location\":{\"structure\":\"cataclysm:burning_arena\"},\"item\":{\"item\":\"cataclysm:burning_ashes\",\"quantity\":2},"
                + "\"reward\":{\"give\":\"minecraft:diamond\",\"coins\":250}}"));
        assertEquals(rl("cataclysm:burning_arena"), quest.location().exactStructure());
        assertNull(quest.entity());                     // derived at runtime from the profile's first boss
        assertEquals(2, quest.fixedItem().quantity());  // also the drop count per kill
        assertEquals(1f, quest.chance());
        assertFalse(quest.repeatable());
        assertEquals(0L, quest.cooldownMillis());
        assertEquals(2, quest.reward().size());
        assertEquals("@item:minecraft:diamond", quest.reward().get(0).action().rewardVar());
    }

    @Test
    void questRulesAreValidatedAtLoad() {
        // a pool or tag needs an explicit entity; a cooldown needs repeatable; a quest item must be one exact ID
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString("{\"location\":{\"structure\":\"cataclysm:*\"},\"item\":\"a:b\"}")));
        assertNotNull(QuestDef.parse(ID, JsonParser.parseString("{\"location\":{\"pool\":\"superiorstory:safe_outposts\"}}")));   // no item and no entity: a visit
        assertNotNull(QuestDef.parse(ID, JsonParser.parseString("{\"location\":{\"pool\":\"superiorstory:boss_lairs\"},\"entity\":\"cataclysm:ignis\"}")));
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString(
            "{\"location\":{\"structure\":\"a:b\"},\"cooldown\":\"1h\"}")));
        assertEquals(3_600_000L, QuestDef.parse(ID, JsonParser.parseString(
            "{\"location\":{\"structure\":\"a:b\"},\"repeatable\":true,\"cooldown\":\"1h\",\"if\":{\"time\":\"night\"}}")).cooldownMillis());
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString(
            "{\"location\":{\"structure\":\"a:b\"},\"item\":\"#forge:ingots\"}")));
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString(
            "{\"location\":{\"structure\":\"a:b\"},\"typo\":1}")));
    }

    @Test
    void questLocationsTakeEveryFormAndTheOldStructureKeyIsGone() {
        QuestDef list = QuestDef.parse(ID, JsonParser.parseString("{\"location\":{\"structure\":[\"a:b\",\"#c:d\"]},\"entity\":\"a:e\"}"));
        assertEquals("structure", list.location().kind());
        assertNull(list.location().exactStructure());
        assertEquals("pool", QuestDef.parse(ID, JsonParser.parseString("{\"location\":{\"pool\":[\"superiorstory:safe_outposts\"]}}")).location().kind());
        QuestDef biome = QuestDef.parse(ID, JsonParser.parseString("{\"location\":{\"biome\":\"#minecraft:is_ocean\"},\"item\":\"minecraft:cod\"}"));
        assertEquals("biome", biome.location().kind());   // a biome needs no entity for its item
        QuestDef fetch = QuestDef.parse(ID, JsonParser.parseString("{\"item\":\"minecraft:cod\",\"mods\":[\"minecraft\"]}"));
        assertNull(fetch.location());                     // a fetch quest: no search, no marker, no visit
        assertEquals(rl("minecraft:cod"), fetch.fixedItem().item().id());
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString("{\"structure\":\"a:b\"}")));
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString(
            "{\"location\":{\"structure\":\"a:b\"},\"entity\":\"a:e\",\"also\":[{\"structure\":\"c:d\",\"entity\":\"a:e\"}]}")));
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString("{\"location\":{\"biome\":\"minecraft:*\"}}")));
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString("{\"location\":{\"cave\":\"a:b\"}}")));
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString("{\"location\":{\"structure\":\"a:b\",\"biome\":\"c:d\"}}")));
    }

    @Test
    void poolsLayerLikeTagsAppendReplaceAndExcludeOverTheDerivedPool() {
        List<IdSelector> derived = List.of(IdSelector.parse("cataclysm:*"));
        StructurePools.PoolFile append = StructurePools.PoolFile.parse(JsonParser.parseString(
            "{\"structures\":[\"irons_spellbooks:*\"],\"exclude\":[\"cataclysm:acropolis\"]}"));
        StructurePools.PoolFile replace = StructurePools.PoolFile.parse(JsonParser.parseString(
            "{\"replace\":true,\"structures\":[\"cataclysm:burning_arena\"]}"));

        StructurePools.Pool derivedOnly = StructurePools.merge(derived, List.of());
        assertTrue(derivedOnly.contains(rl("cataclysm:acropolis"), tag -> false));

        StructurePools.Pool appended = StructurePools.merge(derived, List.of(append));   // adds to the derived layer, excludes after the merge
        assertTrue(appended.contains(rl("cataclysm:burning_arena"), tag -> false));
        assertTrue(appended.contains(rl("irons_spellbooks:catacombs"), tag -> false));
        assertFalse(appended.contains(rl("cataclysm:acropolis"), tag -> false));

        StructurePools.Pool replaced = StructurePools.merge(derived, List.of(append, replace));   // a higher pack discards what lower ones defined
        assertTrue(replaced.contains(rl("cataclysm:burning_arena"), tag -> false));
        assertFalse(replaced.contains(rl("cataclysm:sunken_city"), tag -> false));
        assertFalse(replaced.contains(rl("irons_spellbooks:catacombs"), tag -> false));

        assertNull(StructurePools.merge(null, null));
        assertTrue(StructurePools.merge(null, List.of(append)).contains(rl("irons_spellbooks:catacombs"), tag -> false));
    }

    // ---------------------------------------------------------------- bundled content

    @Test
    void bundledQuestAndPoolFilesLoad() throws Exception {
        try (var quest = getClass().getResourceAsStream("/data/superiorstory/superiorstory/quests/ignis_ashes.json");
             var pool = getClass().getResourceAsStream("/data/superiorstory/superiorstory/pools/safe_outposts.json")) {
            assertNotNull(quest);
            assertNotNull(pool);
            QuestDef def = QuestDef.parse(ID, JsonParser.parseReader(new InputStreamReader(quest, StandardCharsets.UTF_8)));
            assertEquals(new ResourceLocation("cataclysm:burning_ashes"), def.fixedItem().item().id());
            assertFalse(StructurePools.PoolFile.parse(JsonParser.parseReader(new InputStreamReader(pool, StandardCharsets.UTF_8))).structures().isEmpty());
        }
    }

    // ---------------------------------------------------------------- objectives and extra modules

    @Test
    void extraObjectivesAreBossKillsOrDeliveriesAndAreValidatedAtLoad() throws Exception {
        QuestDef quest = QuestDef.parse(ID, JsonParser.parseString("""
            {"location":{"structure":"a:b"},"entity":"a:boss","also":[
              {"location":{"structure":"c:d"},"entity":"c:boss"},
              {"location":{"pool":"superiorstory:safe_outposts"},"deliver":{"tag":"courier","name":"Orin"}}]}"""));
        assertEquals(2, quest.extra().size());
        assertFalse(quest.extra().get(0).deliver());
        assertTrue(quest.extra().get(1).deliver());
        assertEquals(rl("minecraft:villager"), quest.extra().get(1).npc().type());   // the default NPC type
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString(
            "{\"location\":{\"structure\":\"a:b\"},\"entity\":\"a:e\",\"also\":[{\"location\":{\"structure\":\"c:*\"}}]}")));   // a boss objective on a selector needs an entity
        assertThrows(IllegalArgumentException.class, () -> QuestDef.parse(ID, JsonParser.parseString(
            "{\"location\":{\"structure\":\"a:b\"},\"entity\":\"a:e\",\"also\":[{\"location\":{\"structure\":\"c:d\"},\"deliver\":{\"tag\":\"\"}}]}")));
        try (var bundled = getClass().getResourceAsStream("/data/superiorstory/superiorstory/quests/twin_seals.json")) {
            assertNotNull(bundled);
            assertEquals(2, QuestDef.parse(rl("superiorstory:twin_seals"), JsonParser.parseReader(new InputStreamReader(bundled, StandardCharsets.UTF_8))).extra().size());
        }
    }

    @Test
    void weatherMoonAndReputationCompileWithLoadTimeValidation() {
        Dialogue dialogue = parse("""
            {"npc":"x","beats":[{"text":"A","if":{"weather":"thunder","moon":"full_moon","rep":{"id":"wren","min":1}}}]}""");
        assertFalse(dialogue.branches().get("main").get(0).guard().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"if\":{\"weather\":\"hail\"}}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"if\":{\"moon\":\"half\"}}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"if\":{\"rep\":{\"id\":\"w\",\"min\":5,\"max\":1}}}]}"));
        assertNotNull(parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[{\"text\":\"B\",\"deliver\":true,\"rep\":{\"id\":\"w\"},\"loot\":\"minecraft:chests/end_city_treasure\"}]}]}"));
    }

    @Test
    void inlineStructureAndDangerReferencesAreFilledAndTierWordingScales() {
        var filled = Vars.fill("In {structure:cataclysm:burning_arena}, {danger:cataclysm:ignis}.", Map.of(), (kind, ref) -> kind + "=" + ref);
        assertEquals("In structure=cataclysm:burning_arena, danger=cataclysm:ignis.", filled.text());
        assertEquals(List.of("structure", "danger"), filled.spans().stream().map(Vars.Span::kind).toList());
        assertNull(com.mugloved.superiorstory.dialogue.Names.danger(0));
        assertEquals("a legend that has ended armies", com.mugloved.superiorstory.dialogue.Names.danger(10));
        assertFalse(com.mugloved.superiorstory.dialogue.Names.danger(2).equals(com.mugloved.superiorstory.dialogue.Names.danger(9)));
    }

    @Test
    void aSceneKnowsTheQuestsItWorksOnSoTheirFactsNeedNoAuthoring() throws Exception {
        try (var scene = getClass().getResourceAsStream("/data/superiorstory/superiorstory/scenes/villager_ashes.json")) {
            assertNotNull(scene);
            Dialogue dialogue = Dialogue.parse(ID, JsonParser.parseReader(new InputStreamReader(scene, StandardCharsets.UTF_8)));
            assertEquals(java.util.Set.of(rl("superiorstory:ignis_ashes")), dialogue.quests());   // accept, locate, and hand_over all name it
        }
        assertTrue(parse("{\"npc\":\"x\",\"beats\":[\"Hi.\"]}").quests().isEmpty());
        assertNotNull(parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"if\":{\"boss_tier\":{\"min\":7}}}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"if\":{\"boss_tier\":{\"min\":8,\"max\":2}}}]}"));
    }

    @Test
    void reputationDefaultsToTheSpeakerAndDefaultRewardsGrowWithTier() {
        assertNotNull(parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"if\":{\"rep\":{\"min\":1}},\"choices\":[{\"text\":\"B\",\"rep\":2}]}]}"));
        assertNotNull(parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"if\":{\"rep\":3}}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[{\"text\":\"B\",\"rep\":0}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[{\"text\":\"B\",\"rep\":{\"min\":1}}]}]}"));
        assertEquals(25, com.mugloved.superiorstory.server.StoryQuests.derivedCoins(1));
        assertEquals(2_500, com.mugloved.superiorstory.server.StoryQuests.derivedCoins(10));
        assertTrue(com.mugloved.superiorstory.server.StoryQuests.derivedCoins(8) > com.mugloved.superiorstory.server.StoryQuests.derivedCoins(3));
    }

    @Test
    void everyBundledQuestLoads() throws Exception {
        for (String name : java.util.List.of("ignis_ashes", "sunken_proof", "twin_seals", "three_deaths", "courier_letter", "abyssal_offering",
            "shrine_for_tobin", "shrine_for_wren")) {
            try (var quest = getClass().getResourceAsStream("/data/superiorstory/superiorstory/quests/" + name + ".json")) {
                assertNotNull(quest, name);
                QuestDef.parse(rl("superiorstory:" + name), JsonParser.parseReader(new InputStreamReader(quest, StandardCharsets.UTF_8)));
            }
        }
    }
}
