package com.mugloved.superiorstory;

import com.google.gson.JsonParser;
import com.mugloved.superiorstory.api.DialogueOutcome;
import com.mugloved.superiorstory.dialogue.Dialogue;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueTest {
    private static final ResourceLocation ID = new ResourceLocation("superiorstory", "example_dialogue");

    @BeforeAll
    static void registerHooks() {
        TestHooks.ensure();
    }

    private static Dialogue parse(String json) {
        return Dialogue.parse(ID, JsonParser.parseString(json));
    }

    @Test
    void bundledExampleLoadsWithDefaultsFilledIn() throws Exception {
        try (var input = getClass().getResourceAsStream("/data/superiorstory/superiorstory/scenes/example_dialogue.json")) {
            assertTrue(input != null);
            Dialogue dialogue = Dialogue.parse(ID, JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)));
            assertEquals("npc", dialogue.speakerKey());
            assertEquals(Set.of("main", "warning"), dialogue.branches().keySet());
            assertTrue(dialogue.unreachableBranches().isEmpty());

            Dialogue.Line ask = dialogue.branches().get("main").get(2);
            assertEquals("main/2/0", ask.choices().get(0).id());
            assertEquals("warning", ask.choices().get(0).goTo());
            assertEquals(DialogueOutcome.BAD, ask.choices().get(1).outcome());
            assertEquals(Dialogue.END, ask.choices().get(1).goTo());

            Dialogue.Choice accepted = dialogue.branches().get("warning").get(1).choices().get(0);
            assertEquals("example/accepted", accepted.id());
            assertEquals(DialogueOutcome.GOOD, accepted.outcome());
            assertNull(accepted.goTo());
        }
    }

    @Test
    void villagerPrototypeLoadsWithEveryBranchReachable() throws Exception {
        try (var input = getClass().getResourceAsStream("/data/superiorstory/superiorstory/scenes/villager_prototype.json")) {
            assertTrue(input != null);
            Dialogue dialogue = Dialogue.parse(ID, JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)));
            assertEquals("npc", dialogue.speakerKey());
            assertTrue(dialogue.unreachableBranches().isEmpty());
        }
    }

    @Test
    void smallestDialogueIsJustAnNpcAndLines() {
        Dialogue dialogue = parse("{\"npc\":\"minecraft:villager\",\"beats\":[\"Hello.\"]}");
        assertEquals(1, dialogue.branches().get("main").size());
        assertTrue(dialogue.branches().get("main").get(0).choices().isEmpty());
        assertEquals(0, dialogue.priority());
    }

    @Test
    void hookKeysAreAcceptedWhenRegistered() {
        Dialogue dialogue = parse("{\"npc\":\"x\",\"if\":{\"test_flag\":true},\"beats\":[{\"text\":\"A\",\"choices\":"
            + "[{\"text\":\"B\",\"test_action\":\"value\",\"unless\":{\"test_flag\":\"no\"}}]}]}");
        Dialogue.Choice choice = dialogue.branches().get("main").get(0).choices().get(0);
        assertEquals("test_action", choice.actions().get(0).key());
        assertTrue(dialogue.conditional());
    }

    @Test
    void rejectsMalformedAuthoringAtTheLoaderBoundary() {
        assertThrows(IllegalArgumentException.class, () -> parse(
            "{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[{\"text\":\"B\",\"goto\":\"nowhere\"}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[{\"text\":\"B\",\"typo_action\":\"1\"}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "{\"npc\":\"x\",\"beats\":[\"A\"],\"unknown\":true}"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "{\"npc\":\"x\",\"if\":{\"no_such_condition\":\"1\"},\"beats\":[\"A\"]}"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[\"1\",\"2\",\"3\",\"4\",\"5\"]}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "{\"npc\":\"x\",\"beats\":[{\"text\":\"A\",\"choices\":[{\"text\":\"B\",\"id\":\"same\"},{\"text\":\"C\",\"id\":\"same\"}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> parse(
            "{\"npc\":\"x\",\"beats\":[\"A\"],\"branches\":{\"main\":[\"B\"]}}"));
    }

    @Test
    void reportsBranchesNothingCanReach() {
        Dialogue dialogue = parse("{\"npc\":\"x\",\"beats\":[\"A\"],\"branches\":{\"orphan\":[\"B\"]}}");
        assertEquals(Set.of("orphan"), dialogue.unreachableBranches());
    }
}
