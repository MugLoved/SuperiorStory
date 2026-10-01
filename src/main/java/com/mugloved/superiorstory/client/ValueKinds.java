package com.mugloved.superiorstory.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Client resolvers for module value kinds ({@code @<kind>:ref} variables) beyond the built-in item, entity, time, and lang. */
public final class ValueKinds {
    private static final Map<String, Function<String, String>> RESOLVERS = new ConcurrentHashMap<>();

    private ValueKinds() {}

    public static void register(String kind, Function<String, String> resolver) {
        RESOLVERS.put(kind, resolver);
    }

    static String resolve(String kind, String ref) {
        Function<String, String> resolver = RESOLVERS.get(kind);
        return resolver == null ? ref : resolver.apply(ref);
    }
}
