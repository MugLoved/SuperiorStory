package com.mugloved.superiorstory.dialogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialogue placeholders: {@code {structure}} or {@code {look|the ruins}} (text after the bar is the fallback when the
 * variable is unset), plus inline references {@code {item:ns:id}} and {@code {entity:ns:id}}. The server sends the
 * variables with each line and the client fills them in, so translated text works too. {@code {structure:ns:id}} is the
 * place's display name and {@code {danger:ns:entity}} how that boss's Superior Lib tier is spoken of ("a legend that has ended
 * armies"), so a line about a boss can scale with it without the author knowing the tier.
 * A value may start with a prefix
 * the client resolves: {@link #ENTITY} and {@link #ITEM} name a registry entry (localized name), {@link #TIME} is a
 * duration in milliseconds shown as time left, {@link #LANG} is a lang key.
 */
public final class Vars {
    public static final String ENTITY = "@entity:";
    public static final String ITEM = "@item:";
    public static final String TIME = "@time:";
    public static final String LANG = "@lang:";
    /** Starcatcher fish facts ({@code @fishinfo:<part>:<entry>}), resolved on the client from the fish's own restrictions. */
    public static final String FISHINFO = "@fishinfo:";
    public static final int MAX_VARS = 96;
    public static final int MAX_VALUE = 300;
    private static final Pattern PLACEHOLDER = Pattern.compile(
        "\\{(?:(item|entity|structure|danger):([a-z0-9_.-]+:[a-z0-9_./-]+)|([a-z][a-z0-9_]*))(?:\\|([^}]*))?}");

    /**
     * Where a variable's value landed in the filled text; fallbacks are authored prose and get no span.
     *
     * @param key  the variable name, or {@code item} / {@code entity} for an inline reference
     * @param kind {@code ""} for plain text, else {@code entity}, {@code item}, {@code time}, or {@code lang}
     * @param ref  the registry ID for an entity or item reference, else empty
     */
    public record Span(int start, int end, String key, String kind, String ref) {
        public Span(int start, int end, String key) {
            this(start, end, key, "", "");
        }
    }

    public record Filled(String text, List<Span> spans) {}

    /** Turns a prefixed value into display text on the client. */
    @FunctionalInterface
    public interface Resolver {
        String name(String kind, String ref);
    }

    private Vars() {}

    public static String substitute(String text, Map<String, String> vars, UnaryOperator<String> entityName) {
        return fill(text, vars, entityName).text();
    }

    /** Like {@link #substitute}, but also reports which characters came from a variable so the client can color them. */
    public static Filled fill(String text, Map<String, String> vars, UnaryOperator<String> entityName) {
        return fill(text, vars, (kind, ref) -> kind.equals("entity") ? entityName.apply(ref) : ref);
    }

    public static Filled fill(String text, Map<String, String> vars, Resolver resolver) {
        if (text.indexOf('{') < 0) return new Filled(text, List.of());
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        while (matcher.find()) {
            String fallback = matcher.group(4) == null ? "" : matcher.group(4);
            String value;
            String key;
            String kind = "";
            String ref = "";
            boolean set;
            if (matcher.group(1) != null) {   // inline {item:ns:id} / {entity:ns:id}
                kind = matcher.group(1);
                ref = matcher.group(2);
                key = kind;
                value = resolver.name(kind, ref);
                set = true;
            } else {
                key = matcher.group(3);
                value = vars.get(key);
                set = value != null && !value.isBlank();
                if (!set) {
                    value = fallback;
                } else if (value.startsWith(ENTITY)) {
                    kind = "entity";
                    ref = value.substring(ENTITY.length());
                } else if (value.startsWith(ITEM)) {
                    kind = "item";
                    ref = value.substring(ITEM.length());
                } else if (value.startsWith(TIME)) {
                    kind = "time";
                    ref = value.substring(TIME.length());
                } else if (value.startsWith(LANG)) {
                    kind = "lang";
                    ref = value.substring(LANG.length());
                } else if (value.startsWith("@") && value.indexOf(':') > 1) {   // a module's kind, such as @fishinfo:
                    kind = value.substring(1, value.indexOf(':'));
                    ref = value.substring(value.indexOf(':') + 1);
                }
                if (set && !kind.isEmpty()) value = resolver.name(kind, ref);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
            if (set && !value.isEmpty()) spans.add(new Span(out.length() - value.length(), out.length(), key, kind, ref));
        }
        matcher.appendTail(out);
        return new Filled(out.toString(), spans);
    }
}
