package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonElement;

import java.util.List;

/** Phrase-level pool; uses the same entry compiler and selection contract as line pools. */
public record WordPool(List<LinePool.Entry> entries, boolean replace) {
    public WordPool { entries = List.copyOf(entries); }

    public static WordPool parse(JsonElement json) {
        var root = LinePool.root(json, "words", false);
        return new WordPool(LinePool.entries(root, "words"), LinePool.replace(root));
    }
}
