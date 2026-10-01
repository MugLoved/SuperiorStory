package com.mugloved.superiorstory.dialogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns registry IDs into player-facing names, and bearings into compass words. */
public final class Names {
    private static final String[] WINDS = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};

    private Names() {}

    /**
     * {@code minecraft:snowy_plains} becomes "Snowy Plains": the namespace is dropped, digits are removed
     * ({@code village_2} is "Village"), underscores, dashes and slashes become spaces, and every word is capitalized. A path
     * segment repeats nothing the one before it already said: {@code camp/camp} is "Camp" and {@code camp/camp_deep} is "Camp Deep".
     */
    public static String clean(String id) {
        if (id == null) return "";
        List<String> out = new ArrayList<>();
        List<String> previous = List.of();
        for (String segment : id.substring(id.indexOf(':') + 1).replaceAll("[0-9]+", "").split("/")) {
            List<String> words = new ArrayList<>();
            for (String word : segment.replace('_', ' ').replace('-', ' ').trim().split("\\s+")) {
                if (!word.isEmpty()) words.add(word.toLowerCase(Locale.ROOT));
            }
            int shared = 0;
            while (shared < words.size() && shared < previous.size() && words.get(shared).equals(previous.get(shared))) shared++;
            out.addAll(words.subList(shared, words.size()));
            previous = words;
        }
        StringBuilder text = new StringBuilder();
        for (String word : out) {
            if (text.length() > 0) text.append(' ');
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return text.toString();
    }

    /** Compass word for the offset from the player to the target; north is negative Z. */
    public static String direction(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int index = (int) Math.round(((degrees % 360.0) + 360.0) % 360.0 / 45.0) % 8;
        return WINDS[index];
    }

    /** "about" distance: nearest 10 blocks up to 1000, nearest 100 beyond. */
    /** How a boss of this Superior Lib tier is spoken of; null for an unclassified boss (tier 0). */
    public static String danger(int tier) {
        if (tier <= 0) return null;
        if (tier <= 2) return "a lesser guardian";
        if (tier <= 4) return "a dangerous guardian";
        if (tier <= 6) return "a fearsome guardian";
        if (tier <= 8) return "a deadly champion of terrible power";
        return "a legend that has ended armies";
    }

    public static int roundDistance(double blocks) {
        int step = blocks > 1000.0 ? 100 : 10;
        return (int) Math.max(step, Math.round(blocks / step) * (long) step);
    }
}
