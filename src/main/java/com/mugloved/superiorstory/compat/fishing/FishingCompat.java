package com.mugloved.superiorstory.compat.fishing;

import com.mugloved.superiorstory.api.StoryHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;

/** Starcatcher fishing: the {@code fish} quest item source and its gear data. Registered only when Starcatcher is loaded. */
public final class FishingCompat {
    private FishingCompat() {}

    public static void register() {
        StoryHooks.registerItemSource("fish", value -> new FishSource(FishSelection.parse(value)));
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(new FishGear()));
    }
}
