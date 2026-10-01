package com.mugloved.superiorstory;

import com.google.gson.JsonParser;
import com.mugloved.superiorstory.dialogue.Dialogue;
import com.mugloved.superiorstory.dialogue.Names;
import com.mugloved.superiorstory.dialogue.StructureProfile;
import com.mugloved.superiorstory.dialogue.Vars;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureQuestTest {
    private static final ResourceLocation ID = new ResourceLocation("superiorstory", "test");

    @org.junit.jupiter.api.BeforeAll
    static void modules() {
        TestHooks.ensure();
    }

    @Test
    void everyBundledDialogueAndProfileLoads() throws Exception {
        com.mugloved.superiorstory.server.LinePools.install(DialoguePoolsTest.bundled());
        var root = java.nio.file.Path.of(getClass().getResource("/data/superiorstory/superiorstory/").toURI());
        try (var files = java.nio.file.Files.walk(root.resolve("scenes"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                var json = JsonParser.parseString(java.nio.file.Files.readString(file));
                if (!Dialogue.isDialogue(json)) continue;
                var relative = root.resolve("scenes").relativize(file).toString().replace('\\', '/');
                Dialogue dialogue = Dialogue.parse(new ResourceLocation("superiorstory",
                    relative.substring(0, relative.length() - 5)), json);
                dialogue.validateTexts();
                assertTrue(dialogue.unreachableBranches().isEmpty(), file + " has unreachable branches");
            }
        }
        try (var files = java.nio.file.Files.walk(root.resolve("structures"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                assertFalse(StructureProfile.parseFile(JsonParser.parseString(java.nio.file.Files.readString(file))).isEmpty(),
                    file.toString());
            }
        }
        var rewards = new java.util.HashSet<ResourceLocation>();
        try (var files = java.nio.file.Files.walk(root.resolve("reward_pools"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                var relative = root.resolve("reward_pools").relativize(file).toString().replace('\\', '/');
                rewards.add(new ResourceLocation("superiorstory", relative.substring(0, relative.length() - 5)));
                com.mugloved.superiorstory.dialogue.RewardPool.parse(JsonParser.parseString(java.nio.file.Files.readString(file)));
            }
        }
        try (var files = java.nio.file.Files.walk(root.resolve("quests"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                var json = JsonParser.parseString(java.nio.file.Files.readString(file));
                com.mugloved.superiorstory.dialogue.QuestDef.parse(ID, json);
                var reward = json.getAsJsonObject().getAsJsonObject("reward");
                if (reward != null && reward.has("reward_choice")) {
                    var choice = reward.get("reward_choice");
                    var pool = choice.isJsonPrimitive() ? choice.getAsString() : choice.getAsJsonObject().get("pool").getAsString();
                    assertTrue(rewards.contains(new ResourceLocation(pool)), file + " references missing reward pool " + pool);
                }
            }
        }
    }

    @Test
    void namesDropTheNamespaceAndCapitalizeEveryWord() {
        assertEquals("Snowy Plains", Names.clean("minecraft:snowy_plains"));
        assertEquals("Burning Arena", Names.clean("cataclysm:burning_arena"));
        assertEquals("Village Plains", Names.clean("village/plains"));
        assertEquals("The Leviathan", Names.clean("the_leviathan"));
        assertEquals("Village Plains", Names.clean("village/plains_2"));
        assertEquals("Underground Camp", Names.clean("idas:underground_camp/underground_camp"));
        assertEquals("Underground Camp Deep", Names.clean("idas:underground_camp/underground_camp_deep"));
        assertEquals("Bastion Remnant", Names.clean("minecraft:bastion_3_remnant"));
    }

    @Test
    void bearingsBecomeCompassWordsAndDistancesRound() {
        assertEquals("north", Names.direction(0, -100));
        assertEquals("east", Names.direction(100, 0));
        assertEquals("southeast", Names.direction(100, 100));
        assertEquals("west", Names.direction(-100, 5));
        assertEquals(50, Names.roundDistance(47));
        assertEquals(1200, Names.roundDistance(1234));
    }

    @Test
    void placeholdersFillInAndFallBackWhenUnset() {
        Map<String, String> vars = Map.of("structure", "Burning Arena", "boss", Vars.ENTITY + "cataclysm:ignis");
        assertEquals("Go to the Burning Arena.", Vars.substitute("Go to the {structure}.", vars, id -> "?"));
        assertEquals("It looks like the ruins.", Vars.substitute("It looks like {look|the ruins}.", vars, id -> "?"));
        assertEquals("Beware Ignis!", Vars.substitute("Beware {boss}!", vars, id -> id.substring(id.indexOf(':') + 1).equals("ignis") ? "Ignis" : "?"));
        assertEquals("Nothing here: .", Vars.substitute("Nothing here: {lore}.", vars, id -> "?"));
    }

    @Test
    void filledVariablesReportWhereTheyLandButFallbacksDoNot() {
        Map<String, String> vars = Map.of("structure", "Burning Arena", "biome", "Basalt Deltas");
        Vars.Filled filled = Vars.fill("Go to {structure} in the {biome}, near {look|the ruins}.", vars, id -> "?");
        assertEquals("Go to Burning Arena in the Basalt Deltas, near the ruins.", filled.text());
        assertEquals(List.of(new Vars.Span(6, 19, "structure"), new Vars.Span(27, 40, "biome")), filled.spans());
    }

    @Test
    void specificProfilesOverrideModWideDefaults() {
        List<StructureProfile> profiles = StructureProfile.parseFile(JsonParser.parseString("""
            [{"structures":"cataclysm:*","keywords":["cataclysm"],"look":"a strange ruin"},
             {"structures":["cataclysm:burning_arena"],"look":"a blackstone coliseum","keywords":["flame"],
              "boss":["cataclysm:ignis","cataclysm:ignited_revenant"],"name":"The Burning Arena"},
             {"structures":"cataclysm:sunken_city","quest":false}]"""));
        StructureProfile.Merged arena = StructureProfile.merge(profiles, new ResourceLocation("cataclysm:burning_arena"), tag -> false);
        assertEquals("a blackstone coliseum", arena.look());
        assertEquals("The Burning Arena", arena.name());
        assertEquals(List.of("cataclysm", "flame"), arena.keywords());
        assertEquals(new ResourceLocation("cataclysm:ignis"), arena.bosses().get(0));
        assertTrue(arena.quest());
        assertEquals("a strange ruin", StructureProfile.merge(profiles, new ResourceLocation("cataclysm:acropolis"), tag -> false).look());
        assertFalse(StructureProfile.merge(profiles, new ResourceLocation("cataclysm:sunken_city"), tag -> false).quest());
        assertNull(StructureProfile.merge(profiles, new ResourceLocation("minecraft:village"), tag -> false).look());
    }

    @Test
    void tagSelectorsUseTheTagTester() {
        List<StructureProfile> profiles = StructureProfile.parseFile(JsonParser.parseString("{\"structures\":\"#test:towers\",\"look\":\"tall\"}"));
        assertEquals("tall", StructureProfile.merge(profiles, new ResourceLocation("a:b"), tag -> tag.equals(new ResourceLocation("test:towers"))).look());
        assertNull(StructureProfile.merge(profiles, new ResourceLocation("a:b"), tag -> false).look());
    }

    @Test
    void rejectsMalformedProfiles() {
        assertThrows(IllegalArgumentException.class, () -> StructureProfile.parseFile(JsonParser.parseString("{\"look\":\"no structures\"}")));
        assertThrows(IllegalArgumentException.class, () -> StructureProfile.parseFile(JsonParser.parseString("{\"structures\":\"a:b\",\"typo\":1}")));
        assertThrows(IllegalArgumentException.class, () -> StructureProfile.parseFile(JsonParser.parseString("{\"structures\":\"Not A Valid ID\"}")));
    }

    @Test
    void locateLinesVariantsAndStructureDialoguesParse() {
        Dialogue dialogue = Dialogue.parse(ID, JsonParser.parseString("""
            {"npc":"x","beats":[["One.","Two."],
              {"text":"Thinking...","locate":{"radius":2000,"min_size":40,"structure":"cataclysm:*","found":"found","failed":"nothing","waypoint":false}}],
             "branches":{"found":["Found {structure}."],"nothing":["Nothing."]}}"""));
        Dialogue.Line variants = dialogue.branches().get("main").get(0);
        assertEquals(2, variants.texts().size());
        Dialogue.Kind kind = dialogue.branches().get("main").get(1).kind();
        assertNotNull(kind);
        com.mugloved.superiorstory.dialogue.LocateSpec locate = (com.mugloved.superiorstory.dialogue.LocateSpec) kind.spec();
        assertEquals(2000, locate.radiusBlocks());
        assertEquals(40, locate.minSize());
        assertEquals("found", locate.found());
        assertFalse(locate.waypoint());
        assertTrue(dialogue.unreachableBranches().isEmpty());

        Dialogue forStructure = Dialogue.parse(ID, JsonParser.parseString("{\"structure\":\"cataclysm:sunken_city\",\"beats\":[\"Deep.\"]}"));
        assertNull(forStructure.speakerKey());
        assertNotNull(forStructure.structure());
    }

    @Test
    void rejectsBadLocateAndBindingAuthoring() {
        assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, JsonParser.parseString(
            "{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"locate\":{\"found\":\"nowhere\"}}]}")));
        assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, JsonParser.parseString(
            "{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[\"B\"],\"locate\":{}}]}")));
        assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, JsonParser.parseString(
            "{\"npc\":\"x\",\"structure\":\"a:b\",\"beats\":[\"A\"]}")));
    }
}
