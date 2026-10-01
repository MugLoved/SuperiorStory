package com.mugloved.superiorstory;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.Dialogue;
import com.mugloved.superiorstory.dialogue.LinePool;
import com.mugloved.superiorstory.dialogue.Vars;
import com.mugloved.superiorstory.dialogue.WordPool;
import com.mugloved.superiorstory.scene.StoryScene.Text;
import com.mugloved.superiorstory.server.LinePools;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DialoguePoolsTest {
    private static final ResourceLocation ID = new ResourceLocation("test", "greeting");
    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    @BeforeAll static void modules() {
        TestHooks.ensure();
        StoryHooks.registerCondition("pool_flag", value -> context -> context.vars().containsKey(value.getAsString()));
    }

    @AfterEach void clear() { LinePools.install(new LinePools.Catalogue(Map.of(), Map.of())); }

    @Test void weightedGuardsFirstOrderAndEmptyEligibility() {
        StoryContext context = new StoryContext(null, null, null, new LinkedHashMap<>(), null);
        LinePool weighted = LinePool.parse(json("""
            {"lines":[{"text":"A","weight":1},{"text":"B","weight":9},
             {"text":"hidden","if":{"pool_flag":"high"}},{"text":"blocked","unless":{"test_flag":true}}]}
            """));
        int heavy = 0;
        RandomSource random = RandomSource.create(42);
        for (int i = 0; i < 200; i++) {
            int pick = LinePool.pick(weighted.entries(), false, context, random, -1);
            assertTrue(pick == 0 || pick == 1);
            if (pick == 1) heavy++;
        }
        assertTrue(heavy > 150 && heavy < 200, "weights must affect the roll");
        LinePool ordered = LinePool.parse(json("""
            {"mode":"first","lines":[{"text":"high","if":{"pool_flag":"high"}},"fallback"]}
            """));
        assertEquals(1, LinePool.pick(ordered.entries(), true, context, random, 1));
        context.vars().put("high", "yes");
        assertEquals(1, LinePool.pick(ordered.entries(), true, context, random, 0));
        assertEquals(0, LinePool.pick(ordered.entries(), true, context, random, 1));
        context.vars().remove("high");
        assertEquals(1, LinePool.pick(weighted.entries(), false, context, random, 0));
        LinePool empty = LinePool.parse(json("{\"lines\":[{\"text\":\"hidden\",\"unless\":{\"test_flag\":true}}]}"));
        assertEquals(-1, LinePool.pick(empty.entries(), false, context, random, -1));
    }

    @Test void poolSelectionRerollsAcrossTalksAndLoopsAndHistoryIsTransient() {
        var player = java.util.UUID.randomUUID();
        StoryContext context = new StoryContext(null, null, null, new LinkedHashMap<>(), null);
        LinePools.install(new LinePools.Catalogue(Map.of(ID, LinePool.parse(json("{\"lines\":[\"A\",\"B\"]}"))), Map.of()));
        Dialogue dialogue = Dialogue.parse(new ResourceLocation("test", "conversation"), json("""
            {"npc":"x","beats":[{"pool":"greeting","pace":1.4,"accent":true,"choices":[{"text":"Again","goto":"main"}]}]}
            """));
        dialogue.validateTexts();
        var line = dialogue.branches().get("main").get(0);
        assertNotNull(line.source());
        Text first = LinePools.pick(ID, player, context, RandomSource.create(9));
        assertNotEquals(first, LinePools.pick(ID, player, new StoryContext(null, null, null, new LinkedHashMap<>(), null), RandomSource.create(9)));
        assertEquals(first, LinePools.pick(ID, player, context, RandomSource.create(9)));
        assertTrue(line.accent());
        assertEquals(1.4f, line.pace());
        assertEquals(1, line.choices().size());
        LinePools.forget(player);
        assertEquals(first, LinePools.pick(ID, player, context, RandomSource.create(9)));
        LinePools.install(new LinePools.Catalogue(Map.of(ID, LinePool.parse(json("{\"lines\":[\"A\",\"B\"]}"))), Map.of()));
        assertEquals(first, LinePools.pick(ID, player, context, RandomSource.create(9)));
        LinePools.install(new LinePools.Catalogue(Map.of(ID, LinePool.parse(json("{\"lines\":[{\"text\":\"hidden\",\"unless\":{\"test_flag\":true}}]}"))), Map.of()));
        assertNull(LinePools.pick(ID, player, context, RandomSource.create(9)));
    }

    @Test void wordsExpandBeforeVariablesWithIndependentOccurrencesAndOwnNamespaces() throws Exception {
        var player = java.util.UUID.randomUUID();
        Map<ResourceLocation, List<JsonElement>> words = Map.of(
            new ResourceLocation("test", "friend"), List.of(json("{\"words\":[\"friend\",\"traveler\"]}")),
            new ResourceLocation("other", "nested"), List.of(json("{\"words\":[\"{~leaf} {player}\"]}")),
            new ResourceLocation("other", "leaf"), List.of(json("{\"words\":[\"welcome\"]}")));
        LinePools.install(LinePools.compile(Map.of(ID, List.of(json("{\"lines\":[\"{~other:nested}, {~friend}.\"]}"))), words, (file, error) -> fail(file + ": " + error)));
        var context = new StoryContext(null, null, null, new LinkedHashMap<>(), null);
        Text text = LinePools.pick(ID, player, context, RandomSource.create(1));
        assertTrue(text.component().getString().startsWith("welcome {player}, "));
        assertTrue(Vars.substitute(text.component().getString(), Map.of("player", "Alex"), id -> "?").startsWith("welcome Alex, "));
        String twice = LinePools.expand(Text.literal("{~friend}/{~friend}"), "test", player, context, RandomSource.create(1)).component().getString();
        String[] occurrences = twice.split("/");
        assertNotEquals(occurrences[0], occurrences[1]);
        assertEquals(Text.translation("plain.translation"), StoryHooks.prepareText(Text.translation("plain.translation"), "test", context, RandomSource.create(1)));
        var wire = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            text.write(wire);
            assertEquals(text, Text.read(wire));
            assertEquals(0, wire.readableBytes());
        } finally { wire.release(); }
        LinePools.install(new LinePools.Catalogue(Map.of(), Map.of(new ResourceLocation("test", "localized"), WordPool.parse(json("{\"words\":[{\"translate\":\"my.friend\"}]}")))));
        var localized = LinePools.expand(Text.literal("{~localized}"), "test", player, context, RandomSource.create(1));
        assertTrue(localized.words().get("localized").get(0).translate());
        LinePools.install(new LinePools.Catalogue(Map.of(), Map.of(new ResourceLocation("test", "none"), WordPool.parse(json("{\"words\":[{\"text\":\"hidden\",\"unless\":{\"test_flag\":true}}]}")))));
        assertEquals("Hello ", LinePools.expand(Text.literal("Hello {~none}"), "test", player, context, RandomSource.create(1)).component().getString());
        assertEquals(Text.translation("superiorstory.pool.empty"), LinePools.expand(Text.literal("{~none}"), "test", player, context, RandomSource.create(1)));
    }

    @Test void mergeReplaceValidationAndSiblingIsolation() {
        var layers = List.of(json("{\"lines\":[\"lower\"]}"), json("{\"lines\":[\"upper\"]}"));
        var appended = LinePools.compile(Map.of(ID, layers), Map.of(), (file, error) -> fail(error));
        assertEquals(List.of("lower", "upper"), appended.lines().get(ID).entries().stream().map(e -> e.text().value()).toList());
        var replaced = LinePools.compile(Map.of(ID, List.of(layers.get(0), json("{\"replace\":true,\"lines\":[\"only\"]}"))), Map.of(), (file, error) -> fail(error));
        assertEquals(1, replaced.lines().get(ID).entries().size());
        assertEquals(2, LinePools.merge(WordPool.parse(json("{\"words\":[\"a\"]}")), WordPool.parse(json("{\"words\":[\"b\"]}"))).entries().size());
        assertEquals(1, LinePools.merge(WordPool.parse(json("{\"words\":[\"a\"]}")), WordPool.parse(json("{\"replace\":true,\"words\":[\"b\"]}"))).entries().size());
        List<String> errors = new ArrayList<>();
        ResourceLocation bad = new ResourceLocation("test", "bad");
        var compiled = LinePools.compile(Map.of(ID, layers, bad, List.of(json("{\"lines\":[\"{~missing}\"]}"))), Map.of(), (file, error) -> errors.add(file + ": " + error));
        assertTrue(compiled.lines().containsKey(ID));
        assertFalse(compiled.lines().containsKey(bad));
        assertTrue(errors.get(0).contains("line_pools/bad.json"));
        assertTrue(errors.get(0).contains("lines[0].text"));
        LinePools.install(compiled);
        var invalid = Dialogue.parse(ID, json("{\"npc\":\"x\",\"beats\":[{\"pool\":\"absent\"}]}"));
        assertTrue(assertThrows(IllegalArgumentException.class, invalid::validateTexts).getMessage().contains("main[0].pool"));
        var badWord = Dialogue.parse(ID, json("{\"npc\":\"x\",\"beats\":[\"{~missing}\"]}"));
        assertThrows(IllegalArgumentException.class, badWord::validateTexts);
        for (String malformed : List.of("{\"lines\":[]}", "{\"lines\":[\"a\"],\"typo\":1}", "{\"lines\":[{\"text\":\"a\",\"weight\":0}]}", "{\"lines\":[{\"text\":\"a\",\"weight\":1.5}]}", "{\"lines\":[{\"text\":\"a\",\"if\":{\"typo\":true}}]}")) assertThrows(IllegalArgumentException.class, () -> LinePool.parse(json(malformed)));
        assertThrows(IllegalArgumentException.class, () -> Dialogue.parse(ID, json("{\"npc\":\"x\",\"beats\":[{\"text\":\"a\",\"pool\":\"greeting\"}]}")));
        var oversizedWord = new ResourceLocation("test", "large");
        var oversized = LinePools.compile(Map.of(ID, List.of(json("{\"lines\":[\"{~large}{~large}\"]}"))), Map.of(oversizedWord, List.of(json("{\"words\":[\"" + "x".repeat(300) + "\"]}"))), (file, error) -> errors.add(error));
        assertFalse(oversized.lines().containsKey(ID));
        assertTrue(errors.stream().anyMatch(error -> error.contains("expanded text exceeds")));
    }

    @Test void nestedWordsAllowFourLevelsRejectCyclesAndDeeperChains() throws Exception {
        Map<ResourceLocation, List<JsonElement>> words = new LinkedHashMap<>();
        for (int i = 1; i <= 4; i++) words.put(new ResourceLocation("test", "w" + i), List.of(json("{\"words\":[\"" + (i == 4 ? "leaf" : "{~w" + (i + 1) + "}") + "\"]}")));
        var good = LinePools.compile(Map.of(), words, (file, error) -> fail(error));
        LinePools.install(good);
        assertEquals("leaf", LinePools.expand(Text.literal("{~w1}"), "test", java.util.UUID.randomUUID(), new StoryContext(null, null, null, Map.of(), null), RandomSource.create(1)).component().getString());
        words.put(new ResourceLocation("test", "too_deep"), List.of(json("{\"words\":[\"{~w1}\"]}")));
        words.put(new ResourceLocation("test", "cycle"), List.of(json("{\"words\":[\"{~cycle}\"]}")));
        words.put(new ResourceLocation("test", "dependent"), List.of(json("{\"words\":[\"{~cycle}\"]}")));
        var invalid = LinePools.compile(Map.of(), words, (file, error) -> {});
        assertEquals(4, invalid.words().size());
    }

    static LinePools.Catalogue bundled() throws Exception {
        Map<ResourceLocation, List<JsonElement>> lines = bundledFiles("line_pools");
        Map<ResourceLocation, List<JsonElement>> words = bundledFiles("word_pools");
        return LinePools.compile(lines, words, (file, error) -> fail(file + ": " + error));
    }

    private static Map<ResourceLocation, List<JsonElement>> bundledFiles(String kind) throws Exception {
        Path root = Path.of(DialoguePoolsTest.class.getResource("/data/superiorstory/superiorstory/" + kind).toURI());
        Map<ResourceLocation, List<JsonElement>> out = new LinkedHashMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                out.put(new ResourceLocation("superiorstory", relative.substring(0, relative.length() - 5)), List.of(json(Files.readString(file))));
            }
        }
        return out;
    }
}
