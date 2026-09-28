package com.mugloved.superiorstory;

import com.mugloved.superiorstory.network.StoryNetwork;
import net.minecraftforge.fml.common.Mod;

/**
 * SuperiorStory: a short first-join intro for the Superior modpack.
 *
 * Flow: world finishes loading -> intro screen covers the world -> five typed beats ->
 * the last beat asks the player to press their Skill Tree key -> the key press passes
 * straight through to the Skill Tree mod, and the world fades in once they leave it.
 *
 * Plays once per player per world. The world keeps running underneath (so chunks/LODs
 * keep loading) while the player is protected server-side.
 */
@Mod(SuperiorStory.MODID)
public class SuperiorStory {
    public static final String MODID = "superiorstory";

    public SuperiorStory() {
        StoryNetwork.register();
    }
}
