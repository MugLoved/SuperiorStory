package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;

/**
 * The moment a quest marker appears: once the conversation is closed, Superior Skylines plays its map scene (the map zooms
 * open, the view glides to the marker, the waypoint is placed half a second later, and the map zooms closed a second after
 * the last one). Markers that arrive while a conversation is open wait for it to close.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, value = Dist.CLIENT)
final class WaypointReveal {
    private record Marker(int x, int y, int z, String name) {}

    private static final ArrayDeque<Marker> QUEUE = new ArrayDeque<>();

    private WaypointReveal() {}

    /** {@code returning} labels the marker "Return to <name>" instead of naming a structure. */
    static void add(int x, int y, int z, String name, boolean returning) {
        String label = returning ? I18n.get("superiorstory.waypoint.return", name) : name;
        if (StorySkylines.available()) QUEUE.add(new Marker(x, y, z, label));
    }

    static void remove(int x, int z) {
        QUEUE.removeIf(marker -> marker.x == x && marker.z == z);
        StorySkylines.remove(x, z);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            QUEUE.clear();
            return;
        }
        if (QUEUE.isEmpty() || (mc.screen != null && !StorySkylines.mapOpen())) return;   // wait for the conversation (or any screen) to close
        Marker marker;
        while ((marker = QUEUE.poll()) != null) StorySkylines.reveal(marker.x, marker.y, marker.z, marker.name, StoryAudio::ping);
    }
}
