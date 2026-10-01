package com.mugloved.superiorstory;

import com.mugloved.superiorstory.compat.bountiful.BountifulBridge;
import com.mugloved.superiorstory.compat.ftbquests.FtbQuestsDialogueBridge;
import com.mugloved.superiorstory.compat.shop.ShopCoinsBridge;
import com.mugloved.superiorstory.module.CoreModules;
import com.mugloved.superiorstory.network.StoryNetwork;
import com.mugloved.superiorstory.server.QuestDropsModifier;
import com.mugloved.superiorstory.sound.StorySoundEvents;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * SuperiorStory: a short first-join intro for the Superior modpack, plus NPC dialogue scenes.
 *
 * Flow: world finishes loading -> intro screen covers the world -> five typed beats ->
 * the last beat asks for the installed Skill Tree key and opens that tree;
 * the world fades in once they leave it.
 *
 * Plays once per player per world. The world keeps running underneath (so chunks/LODs
 * keep loading) while Superior Respawn protects the player server-side.
 *
 * Dialogue: press the Talk key at a bound NPC or block; it freezes and faces the player, the HUD fades out, and the
 * authored JSON conversation plays. Every choice posts a Forge event (see the api package).
 */
@Mod(SuperiorStory.MODID)
public class SuperiorStory {
    public static final String MODID = "superiorstory";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SuperiorStory() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        StorySoundEvents.REGISTER.register(modBus);
        QuestDropsModifier.REGISTER.register(modBus);
        StoryNetwork.register();
        registerModules();
    }

    /** Module keys must exist before datapack scenes load so typos are rejected at load time. */
    private static void registerModules() {
        CoreModules.register();
        if (ModList.get().isLoaded("ftbquests")) FtbQuestsDialogueBridge.register();
        if (ModList.get().isLoaded("superior_shop")) {
            ShopCoinsBridge.register();
        }
        if (ModList.get().isLoaded("bountiful")) BountifulBridge.register();
        if (ModList.get().isLoaded("starcatcher")) {
            com.mugloved.superiorstory.compat.fishing.FishingCompat.register();
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () ->
                com.mugloved.superiorstory.client.ValueKinds.register("fishinfo", com.mugloved.superiorstory.compat.fishing.FishFactsClient::resolve));
        }
    }
}
