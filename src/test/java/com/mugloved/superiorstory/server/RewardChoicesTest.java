package com.mugloved.superiorstory.server;

import com.mugloved.superiorstory.dialogue.Guard;
import com.mugloved.superiorstory.dialogue.RewardPool;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardChoicesTest {
    @Test
    void freshOffersExcludePreviousChoicesAndFillSmallerGuardedPools() {
        RewardPool pool = new RewardPool(IntStream.range(0, 42)
            .mapToObj(i -> new RewardPool.Entry(i + 1, "Reward " + i, Guard.EMPTY, List.of())).toList());
        List<Integer> eligible = IntStream.range(0, 42).boxed().toList();
        RandomSource random = RandomSource.create(17);
        int[] previous = new int[0];
        for (int n = 0; n < 100; n++) {
            List<Integer> offered = RewardChoices.roll(pool, eligible, previous, 4, random);
            assertEquals(4, new HashSet<>(offered).size());
            for (int index : previous) assertFalse(offered.contains(index));
            previous = offered.stream().mapToInt(Integer::intValue).toArray();
        }
        List<Integer> limited = RewardChoices.roll(pool, List.of(0, 1, 2, 3, 4), new int[]{0, 1, 2, 3}, 4, random);
        assertEquals(4, new HashSet<>(limited).size());
        assertTrue(limited.contains(4));
        assertTrue(List.of(0, 1, 2, 3, 4).containsAll(limited));
    }
}
