package com.mugloved.superiorstory;

import com.google.gson.JsonParser;
import com.superior.sounds.audio.AudioCatalogLoader;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryAudioCatalogTest {
    @Test
    void introCatalogCompilesThroughSuperiorSounds() throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/superiorstory/superior_sounds/audio/intro.json")) {
            assertTrue(input != null);
            var catalog = AudioCatalogLoader.parseCatalogObject(
                JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject(),
                "superiorstory"
            );
            assertTrue(catalog.errors().isEmpty(), catalog.errors().toString());
            assertEquals(6, catalog.rules().size());
            assertEquals(6, catalog.tracks().size());
        }
    }
}
