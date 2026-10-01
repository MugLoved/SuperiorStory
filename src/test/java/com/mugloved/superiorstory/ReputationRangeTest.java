package com.mugloved.superiorstory;

import com.google.gson.JsonParser;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.compat.bountiful.BountifulBridge;
import com.mugloved.superiorstory.module.ExtraModules;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReputationRangeTest {
    @Test void oneWriterAndReaderClampAndConditionsValidateAtLoad() throws Exception {
        TestHooks.ensure();
        var player = new CompoundTag();
        var reps = new CompoundTag();
        reps.putInt("guild", Integer.MAX_VALUE);
        player.put("superiorstory_rep", reps);
        assertEquals(ExtraModules.REP_MAX, ExtraModules.reputation(player, "guild"));
        assertEquals(Integer.MAX_VALUE, reps.getInt("guild"), "read does not migrate saved data");
        ExtraModules.addReputation(player, "guild", -1);
        assertEquals(ExtraModules.REP_MAX - 1, ExtraModules.reputation(player, "guild"));
        ExtraModules.addReputation(player, "guild", Integer.MAX_VALUE);
        assertEquals(ExtraModules.REP_MAX, reps.getInt("guild"));
        ExtraModules.addReputation(player, "guild", Integer.MIN_VALUE);
        assertEquals(ExtraModules.REP_MIN, reps.getInt("guild"));
        reps.putInt("guild", Integer.MIN_VALUE);
        assertEquals(ExtraModules.REP_MIN, ExtraModules.reputation(player, "guild"));
        assertNotNull(StoryHooks.condition("rep").apply(JsonParser.parseString("{\"id\":\"guild\",\"max\":" + ExtraModules.REP_MIN + "}")));
        for (String invalid : new String[]{"{\"min\":" + (ExtraModules.REP_MAX + 1) + "}", "{\"max\":" + (ExtraModules.REP_MIN - 1) + "}", "{\"min\":5,\"max\":1}", Integer.toString(ExtraModules.REP_MAX + 1)}) {
            assertThrows(IllegalArgumentException.class, () -> StoryHooks.condition("rep").apply(JsonParser.parseString(invalid)));
        }
        assertNotNull(StoryHooks.action("rep").apply(JsonParser.parseString(Integer.toString(Integer.MAX_VALUE))));
    }

    @Test void scaleUnlocksTheAgreedBountifulTiers() {
        assertEquals(ExtraModules.REP_MIN * BountifulBridge.BOUNTIFUL_REP_SCALE, BountifulBridge.bountifulRep(ExtraModules.REP_MIN));
        assertEquals(ExtraModules.REP_MAX * BountifulBridge.BOUNTIFUL_REP_SCALE, BountifulBridge.bountifulRep(ExtraModules.REP_MAX));
        int[] storyThresholds = {2, 5, 9, 10};
        int[] bountifulThresholds = {5, 15, 25, 30};
        for (int i = 0; i < storyThresholds.length; i++) {
            assertTrue(BountifulBridge.bountifulRep(storyThresholds[i]) >= bountifulThresholds[i]);
            assertTrue(BountifulBridge.bountifulRep(storyThresholds[i] - 1) < bountifulThresholds[i]);
        }
    }
}
