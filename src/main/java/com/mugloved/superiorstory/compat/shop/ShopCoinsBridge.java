package com.mugloved.superiorstory.compat.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryHooks;
import com.superior.shop.api.shop.CurrencyGrantDisplayMode;
import com.superior.shop.api.shop.SuperiorShopServices;

import java.util.Set;

/**
 * The {@code coins} action, loaded only when Superior Shop is present: {@code "coins": 250} pays the default currency, or
 * {@code "coins": {"amount": 250, "currency": "ns:id"}} a named one. A currency the Shop does not know pays the same amount
 * in the default currency and logs one warning, so a quest never breaks over a typo.
 */
public final class ShopCoinsBridge {
    static final String DEFAULT_CURRENCY = "internal:coins";
    private static boolean warned;

    private ShopCoinsBridge() {}

    public static void register() {
        StoryHooks.registerAction("coins", ShopCoinsBridge::compile);
    }

    private static StoryHooks.Action compile(JsonElement value) {
        double amount;
        String currency = DEFAULT_CURRENCY;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            amount = value.getAsDouble();
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : object.keySet()) if (!Set.of("amount", "currency").contains(key)) throw new IllegalArgumentException("Unknown coins field: " + key);
            if (!object.has("amount") || !object.get("amount").isJsonPrimitive() || !object.get("amount").getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("amount must be a number");
            }
            amount = object.get("amount").getAsDouble();
            if (object.has("currency")) {
                if (!object.get("currency").isJsonPrimitive() || !object.get("currency").getAsJsonPrimitive().isString()) throw new IllegalArgumentException("currency must be a string");
                currency = object.get("currency").getAsString();
            }
        } else {
            throw new IllegalArgumentException("coins is a number or {\"amount\": n, \"currency\": \"ns:id\"}");
        }
        if (!(amount > 0) || Double.isInfinite(amount)) throw new IllegalArgumentException("amount must be above 0");
        double paid = amount;
        String wanted = currency;
        return context -> {
            String source = context.sourceId() == null ? "superiorstory:dialogue" : "superiorstory:" + context.sourceId();
            if (SuperiorShopServices.grantCurrency(context.player(), wanted, paid, source, CurrencyGrantDisplayMode.GAIN_POPUP)) return;
            if (!wanted.equals(DEFAULT_CURRENCY)) {
                if (!warned) SuperiorStory.LOGGER.warn("Currency {} is not registered; paying {} in {}", wanted, paid, DEFAULT_CURRENCY);
                warned = true;
                SuperiorShopServices.grantCurrency(context.player(), DEFAULT_CURRENCY, paid, source, CurrencyGrantDisplayMode.GAIN_POPUP);
            }
        };
    }
}
