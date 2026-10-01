package com.mugloved.superiorstory;

import com.google.gson.JsonParser;
import com.mugloved.superiorstory.scene.StoryScene;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorySceneTest {
    private static final ResourceLocation ID = new ResourceLocation("superiorstory", "intro");

    @Test
    void bundledReplicaLoadsAndSurvivesNetworkSnapshot() throws Exception {
        try (var input = getClass().getResourceAsStream("/data/superiorstory/superiorstory/scenes/intro.json")) {
            assertTrue(input != null);
            StoryScene scene = StoryScene.parse(ID,
                JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)));
            assertFalse(scene.firstJoin());
            assertEquals(StoryScene.End.SKILL_TREE, scene.end());
            assertEquals(5, scene.beats().size());
            for (int i = 0; i < 5; i++) {
                assertEquals("superiorstory.beat." + (i + 1), scene.beats().get(i).text().value());
                assertTrue(scene.beats().get(i).text().translate());
            }
            assertTrue(scene.beats().get(2).accent());
            assertEquals(2, scene.beats().get(2).choices().size());
            assertEquals("superiorstory.choice.where", scene.beats().get(2).choices().get(0).value());
            assertEquals("superiorstory.choice.who", scene.beats().get(2).choices().get(1).value());
            assertEquals(2.1f, scene.beats().get(2).pace());
            assertEquals(1.2f, scene.beats().get(4).pace());

            FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
            try {
                scene.writeClient(packet);
                assertEquals(scene.beats(), StoryScene.readClient(ID, packet).beats());
            } finally {
                packet.release();
            }
        }
    }

    @Test
    void rejectsMalformedAuthoringAtTheLoaderBoundary() {
        assertThrows(IllegalArgumentException.class, () -> StoryScene.parse(ID,
            JsonParser.parseString("{\"beats\":[{\"text\":\"A\",\"choices\":[\"Only one\"]}]}")));
        assertThrows(IllegalArgumentException.class, () -> StoryScene.parse(ID,
            JsonParser.parseString("{\"beats\":[\"A\"],\"unknown\":true}")));
    }
}
