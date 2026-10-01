package com.mugloved.superiorstory;

import com.google.gson.JsonParser;
import com.mugloved.superiorstory.dialogue.UnlockSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared value grammar of {@code unlock}, {@code lock}, and {@code unlocked}. */
class UnlockSpecTest {
    private static UnlockSpec parse(String json) {
        return UnlockSpec.parse(JsonParser.parseString(json), "unlock");
    }

    @Test
    void everyFormResolvesToKeys() {
        assertEquals(List.of("ignis_wares"), parse("\"ignis_wares\"").keys());
        assertEquals(List.of("category:dragon_eggs"), parse("{\"category\": \"dragon_eggs\"}").keys());
        UnlockSpec mixed = parse("[\"bundle\", {\"category\": \"a\", \"entry\": \"b:c\"}, {\"keys\": [\"x\", \"bundle\"], \"notify\": false}]");
        assertEquals(List.of("bundle", "category:a", "entry:b:c", "x"), mixed.keys());
        assertFalse(mixed.tell());
        assertTrue(parse("\"k\"").tell());
    }

    @Test
    void badValuesAreRejectedAtLoad() {
        assertThrows(IllegalArgumentException.class, () -> parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"categry\": \"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"category\": \"\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"category\": \"x\", \"notify\": \"no\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("5"));
    }
}
