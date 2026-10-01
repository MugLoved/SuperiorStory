package com.mugloved.superiorstory;

import com.mugloved.superiorstory.api.StoryHooks;

/** Registers the modules the mod constructor normally registers, once per test JVM, plus two test-only keys. */
final class TestHooks {
    private static boolean registered;

    private TestHooks() {}

    /** Bountiful's keys, absent in tests: the real spec parsers, a no-op job, and stub actions and conditions. */
    private static void registerBountyKeys() {
        StoryHooks.registerLineKind("bounty", new StoryHooks.LineKind() {
            @Override public Object compile(com.google.gson.JsonElement value, String where) { return com.mugloved.superiorstory.compat.bountiful.BountySpec.parse(value, where); }
            @Override public Job start(com.mugloved.superiorstory.api.StoryContext context, Object spec) { throw new UnsupportedOperationException(); }
        });
        StoryHooks.registerAction("bounty_accept", value -> context -> { });
        StoryHooks.registerAction("bounty_turn_in", value -> context -> { });
        StoryHooks.registerCondition("bounty_ready", value -> context -> false);
        StoryHooks.registerCondition("bounty_active", value -> context -> false);
    }

    static synchronized void ensure() {
        if (registered) return;
        // the real built-ins: the bundled dialogues and quests are validated against them
        com.mugloved.superiorstory.module.CoreModules.register();
        StoryHooks.registerAction("coins", value -> context -> { });   // Superior Shop's key, absent in tests
        registerBountyKeys();
        StoryHooks.registerItemSource("fish", value -> {   // Starcatcher's source, absent in tests: the real parser, no picks
            com.mugloved.superiorstory.compat.fishing.FishSelection.parse(value);
            return context -> java.util.List.of();
        });
        StoryHooks.registerAction("test_action", value -> context -> { });
        StoryHooks.registerCondition("test_flag", value -> context -> true);
        registered = true;
    }
}
