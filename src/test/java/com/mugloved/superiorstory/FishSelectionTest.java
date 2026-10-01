package com.mugloved.superiorstory;

import com.google.gson.JsonParser;
import com.mugloved.superiorstory.compat.fishing.FishSelection;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code fish} source's filters, distinct kinds, quantity, and preference for uncaught species. */
class FishSelectionTest {
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");
    private static final ResourceLocation LAVA = new ResourceLocation("minecraft", "lava");

    private static FishSelection.Candidate fish(String id, String rarity, Set<ResourceLocation> dimensions, Set<ResourceLocation> fluids) {
        return new FishSelection.Candidate(new ResourceLocation("sc", id), new ResourceLocation("sc", id), rarity, dimensions, fluids, Set.of(), List.of());
    }

    private static final List<FishSelection.Candidate> CATALOGUE = List.of(
        fish("cod", "common", Set.of(OVERWORLD), Set.of()),
        fish("salmon", "common", null, Set.of()),
        fish("perch", "common", Set.of(OVERWORLD), Set.of()),
        fish("ember", "common", Set.of(NETHER), Set.of(LAVA)),
        fish("pike", "rare", Set.of(OVERWORLD), Set.of()),
        fish("boot", "trash", Set.of(OVERWORLD), Set.of()));

    private static FishSelection parse(String json) {
        return FishSelection.parse(JsonParser.parseString(json));
    }

    @Test
    void filtersKindsAndQuantitySelectCorrectly() {
        FishSelection water = parse("{\"rarity\":\"common\",\"dimension\":\"minecraft:overworld\",\"fluid\":\"minecraft:water\",\"kinds\":3,\"quantity\":4}");
        for (int seed = 0; seed < 20; seed++) {
            List<FishSelection.Candidate> picked = water.pick(CATALOGUE, Set.of(), new Random(seed));
            Set<String> ids = new HashSet<>();
            picked.forEach(entry -> ids.add(entry.entry().getPath()));
            assertEquals(Set.of("cod", "salmon", "perch"), ids);   // distinct, common, overworld (salmon: any dimension), water
        }
        assertEquals(4, water.quantity());
        assertTrue(parse("{\"rarity\":\"common\",\"kinds\":3,\"fluid\":\"minecraft:lava\"}").pick(CATALOGUE, Set.of(), new Random(1)).isEmpty());   // too few
        assertEquals("ember", parse("{\"fluid\":\"minecraft:lava\"}").pick(CATALOGUE, Set.of(), new Random(1)).get(0).entry().getPath());
        assertTrue(parse("{}").pick(CATALOGUE, Set.of(), new Random(2)).stream().noneMatch(entry -> entry.rarity().equals("trash")));
    }

    @Test
    void uncaughtSpeciesComeFirstAndBadValuesFailAtLoad() {
        Set<ResourceLocation> caught = Set.of(new ResourceLocation("sc", "cod"), new ResourceLocation("sc", "salmon"));
        FishSelection one = parse("{\"rarity\":\"common\",\"dimension\":\"minecraft:overworld\"}");
        for (int seed = 0; seed < 10; seed++) assertEquals("perch", one.pick(CATALOGUE, caught, new Random(seed)).get(0).entry().getPath());
        assertEquals(1, parse("{\"rarity\":\"common\",\"dimension\":\"minecraft:overworld\",\"new\":false}").pick(CATALOGUE, caught, new Random(3)).size());
        assertThrows(IllegalArgumentException.class, () -> parse("{\"rarity\":\"trash\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"kinds\":4}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"quantity\":17}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"biome\":\"x:y\"}"));
    }
}
