package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

/** Asks Superior HUD, when installed, to fade the whole HUD out while a dialogue is open. */
final class StoryHud {
    private static boolean warned;

    private StoryHud() {}

    static void hold(boolean held) {
        if (!ModList.get().isLoaded("superior_hud")) return;
        try {
            Holds.hold(held);
        } catch (RuntimeException | LinkageError exception) {
            if (!warned) SuperiorStory.LOGGER.warn("Superior HUD presentation hold unavailable: {}", exception.toString());
            warned = true;
        }
    }

    /** Separate class so Superior HUD types are only linked when the mod is present. */
    private static final class Holds {
        private static final ResourceLocation OWNER = new ResourceLocation(SuperiorStory.MODID, "dialogue");

        static void hold(boolean held) {
            com.superior.hud.api.SuperiorHudApi.get().presentationHolds().hold(OWNER, held);
        }
    }
}
