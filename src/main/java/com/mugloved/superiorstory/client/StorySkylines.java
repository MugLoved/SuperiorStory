package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.network.DialoguePackets;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;

/** Marks a located structure on the Superior Skylines map, when that mod is installed. */
final class StorySkylines {
    private static boolean warned;

    private StorySkylines() {}

    static boolean available() {
        return ModList.get().isLoaded("superior_skylines");
    }

    static void add(int x, int y, int z, String name) {
        if (!available()) return;
        try {
            Marker.add(x, y, z, name);
        } catch (RuntimeException | LinkageError exception) {
            warn(exception);
        }
    }

    /** Plays Skylines' map scene for this marker; {@code onPlaced} runs when the waypoint appears. */
    static void reveal(int x, int y, int z, String name, Runnable onPlaced) {
        if (!available()) return;
        try {
            Marker.reveal(x, y, z, name, onPlaced);
        } catch (RuntimeException | LinkageError exception) {
            warn(exception);
        }
    }

    /** Whether the Skylines map screen is open right now. */
    static boolean mapOpen() {
        if (!available()) return false;
        try {
            return Marker.mapOpen();
        } catch (RuntimeException | LinkageError exception) {
            warn(exception);
            return false;
        }
    }

    static void remove(int x, int z) {
        if (!available()) return;
        try {
            Marker.remove(x, z);
        } catch (RuntimeException | LinkageError exception) {
            warn(exception);
        }
    }

    private static void warn(Throwable exception) {
        if (!warned) SuperiorStory.LOGGER.warn("Superior Skylines waypoint unavailable: {}", exception.toString());
        warned = true;
    }

    /** Separate class so Superior Skylines types are only linked when the mod is present. */
    private static final class Marker {
        static void add(int x, int y, int z, String name) {
            com.alexh.superior_skylines.client.map.SuperiorSkylinesWaypoint.addNamed(Minecraft.getInstance(), x,
                y == DialoguePackets.NO_Y ? null : (double) y, z, name,
                com.alexh.superior_skylines.client.map.SuperiorSkylinesWaypoint.KIND_QUEST);
        }

        static void reveal(int x, int y, int z, String name, Runnable onPlaced) {
            com.alexh.superior_skylines.client.map.SuperiorSkylinesWaypointReveal.reveal(x, y == DialoguePackets.NO_Y ? null : (double) y, z,
                name, com.alexh.superior_skylines.client.map.SuperiorSkylinesWaypoint.KIND_QUEST, onPlaced);
        }

        static boolean mapOpen() {
            return Minecraft.getInstance().screen instanceof com.alexh.superior_skylines.client.map.SuperiorSkylinesMapScreen;
        }

        static void remove(int x, int z) {
            com.alexh.superior_skylines.client.map.SuperiorSkylinesWaypointReveal.cancel(x, z);
            com.alexh.superior_skylines.client.map.SuperiorSkylinesWaypoint.removeAt(Minecraft.getInstance(), x, z);
        }
    }
}
