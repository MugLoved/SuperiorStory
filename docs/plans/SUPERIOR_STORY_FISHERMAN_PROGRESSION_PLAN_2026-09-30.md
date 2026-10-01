# Superior Story Fisherman Progression Plan

Status: active
Doc Type: execution-plan
Owner: `addons/superior_story`
Authoritative For: the fisherman guide progression, quest `location` modules (replacing the quest `structure` field), the Starcatcher fishing compat modules, the `profession` condition, the retirement of Tide's fishing gear (Superior Tweaks batch), the Shop fishing stock, per-player unlock keys owned by Superior Lib (moved from Superior Shop), Superior Lib recipe locks, Story's `unlock` / `lock` / `unlocked` modules, Superior Shop item rarity (rarity sources, `hoverTheme: 'auto'`), and the derived sea creature collection
Supersedes: the quest-level `structure` field described in `SUPERIOR_STORY_MODULAR_AUTHORING_PLAN_2026-09-29.md` and `SUPERIOR_STORY_OBJECTIVES_AND_REPUTATION_PLAN_2026-09-29.md` (quest and `also` objective targets only; `locate` line targets and structure-bound dialogues keep `structure`); in `/home/alexh/superior-linux/addons/superior_shop/docs/plans/SHOP_QUEST_UNLOCKS_PLAN_2026-09-30.md`, the decision that Shop stores unlock state and the Story names `shop_unlock` / `shop_lock` / `shop_unlocked` (Shop keeps its gates, key vocabulary, and lock presentation)
Superseded By: none
Last Updated: 2026-09-30 America/Denver

## 1. Outcome and scope

The operator asked for a fishing progression driven by Superior Story: the player talks to a fisherman villager, is asked to bring specific fish, is told which biome and conditions catch them, and advances through tiers of rarer fish, better tackle, and new places. Fishing runs entirely through Starcatcher, which already catches every Tide fish. Tide's rods and tackle are retired in Superior Tweaks. The guide stays separate from Bountiful. Quest rewards unlock both Shop stock and crafting recipes, through one set of per-player unlock keys that the operator placed in Superior Lib. Each quest chooses how many fish it asks for: one kind, several of one kind, or several kinds. The design uses the mods' own data, so a mod that adds Starcatcher fish or tackle appears in the progression with no Story authoring.

Included:

- Story: quest `location` modules (`structure`, `pool`, `biome`) replacing the quest `structure` field; optional location (fetch quests); an item-source module kind with a Starcatcher `fish` source (kinds and quantity per quest); fish facts; the `profession` condition; `mods` on quest files; core `unlock` / `lock` / `unlocked` modules replacing the Shop-specific ones; the fisherman guide content, its recipe lock files, and its Shop stock.
- Superior Lib: the per-player unlock key store, its change event, client sync, and operator command (moved from Shop); recipe locks for the crafting grid and smithing table.
- Superior Shop: reads unlock keys from Lib and drops its own store; item rarity sources with a Starcatcher source, the `hoverTheme: 'auto'` option, and the KubeJS `SuperiorShop.rarity` lookup.
- Superior Tweaks: retire Tide fishing gear (recipes, loot modifiers, fisherman trade, code-added drops, advancements, EMI visibility).
- Runtime workspace: Tide config; the Shop catalog source `items.js` (operator-approved).

Excluded:

- Bountiful bounties, decrees, reputation, and the `bounty` line kind are unchanged. Starcatcher's bundled Bountiful pools reference `starcatcher:fish_caught`, but Starcatcher registers its trigger as `starcatcher:minigame_completed` (`FishCaughtTrigger.java`, `SCModEvents.java:61`); fixing that is bounty-side work outside this plan.
- No bridge that makes Tide rods run Starcatcher fishing, no converter from Tide-format fish data, no changes to Tide or Starcatcher jars.
- Tide fish items, fish mobs, buckets, food, blocks, and the informational items that read world state (pocket watch, lunar calendar, depth meter, climate gauge, weather radio) remain Tide content; the fish finder reads Tide's fish rules and is retired in B2. The Tide creative tab is not filtered.
- Recipe locks cover the vanilla crafting grid and the smithing table only (D10). No per-recipe locks in recipe viewers, automated crafters, or storage-network crafting terminals.
- Exposing unlock keys through `SuperiorGrantApi` (so other systems such as the skill tree could grant keys) is not included.
- The operator is removing the Tide-format fish in `kubejs/data/*/fishing/fish/`; that removal is not part of this plan.

Authorization: this document is planning only. Implementation, validation, and deployment start on a later operator instruction.

## 2. Evidence this plan relies on

Verified decompiles (`decompiled/INDEX.tsv`): Tide 2.1.1 (`tide-forge-1.20.1-2.1.1-hotfix.jar`, sha256 `cac8ec3ee877...`) and Starcatcher 3.1.4.6 (`starcatcher-3.1.4.6-FORGE-1.20.1.jar`, sha256 `81ccc1c13323...`).

Fishing:

- Starcatcher auto-enables its built-in `tide_compat` datapack (`SCModEvents.addPackFinders`, `shouldAddAutomatically` true). With the KubeJS fish removed, every installed Tide-format fish (105, including the vanilla fish Tide lists) has a Starcatcher entry. The Starcatcher fish registry (`starcatcher:fish`, `Starcatcher.FISH_REGISTRY_KEY`) holds 223 guide fish: 55 common, 52 uncommon, 49 rare, 42 epic, 23 legendary, 2 trash.
- Starcatcher fluids: `minecraft:water` (66 Tide fish), `minecraft:lava` (11), `minecraft:empty` (24, Tide's void fish, cast into open air low in the dimension; the bobber hovers only with a `starcatcher:no_gravity` item such as the Cloud Bobber). `tide_compat` relocates some fish (for example `tide:alpha_fish` becomes a cold-ocean surface catch), so hints must come from Starcatcher, not Tide.
- Every Starcatcher rod is the same `StarcatcherFishingRodItem` (128 durability). Behavior comes from `starcatcher:modifiers` data map entries on the rod, its hook, bobber, and bait, inventory items, curios, effects, and enchantments (`Modifier.getModifiers`). Rod variants are smithing skins (`starcatcher:starcatcher_rod_smithing_recipe`, a smithing recipe). Other tackle uses vanilla shaped and shapeless crafting. Gear slots accept items by tag (`#starcatcher:hooks`, `#starcatcher:bobbers`, `#starcatcher:baits`). Lava fish need `starcatcher:survives_lava` (Amethyst Hook, netherite upgrade template).
- `AbstractFishRestriction.getDescription(level, fp, player, Context)` renders each restriction's own text (its `translation_override` or its type's wording). Restriction types registered by other mods inherit this.
- `FishingGuideAttachment.getFishesCaught(player)` records the fish entries a player has caught. Starcatcher posts Forge `ItemFishedEvent` and awards `minecraft:fish_caught` on every completed catch (`FishApi.spawnFishFromPlayerFishing`). With `give_rod` (default true) a player's first vanilla-rod catch also yields a Starcatcher rod and the Fishing Guide (`SCEvents.itemFished`).

Tide:

- Gear sources: 59 recipe files produce retired items; three global loot modifiers (`tide:add_blazing_fishing_rod` in `minecraft:chests/nether_bridge`, `tide:add_echo_fishing_rod` in `minecraft:chests/ancient_city`, `tide:add_prismarine_fishing_rod` on `minecraft:entities/elder_guardian`); one level-5 fisherman trade added in code (`com.li64.tide.loaders.forge.ForgeEventHandler.modifyVillagerTrades`, line 100); a 5% Honeycomb rod drop on honeycomb harvest (`mixin/BeehiveBlockMixin.java`). The Sunflower rod comes only from Tide's own fishing loot, which stops with Tide's hook. Tide records journal entries only from its own hook (`TideUtils.tryLogCatch` callers). All 12 Tide advancements depend on retired gear or Tide's hook (the root needs `#c:tools/fishing_rod`; `catch_legendary` descends from `get_bait`).

Unlocks and the Shop:

- The unlock key store lives in Shop today: `service/UnlockService.java` (`isUnlocked`, `unlock`, `lock`, `keys`, operator bypass), `persistence/PlayerUnlocksSavedData.java` (storage ID `superior_shop_unlocks`), `service/UnlockKeys.java` (`category:` / `entry:` prefixes), `api/shop/SuperiorShopServices.java`, KubeJS `SuperiorShop.unlock/lock/isUnlocked`, and `/superior_shop unlock|lock`. `SuperiorShopMod.unlock` (line 1049) re-syncs the player's shop and prints `superior_shop.unlock.notice` with `unlockDisplayName` (line 1080), which falls back to the raw key for free-form keys.
- Shop does not depend on Superior Lib today (its `mods.toml` lists forge, minecraft, ftblibrary, kubejs, ftbquests, legendarytabs, cloth_config). Story already depends on Lib (`build.gradle:50`, `mods.toml:29`).
- Story's Shop unlock modules: `compat/shop/ShopUnlockBridge.java` and `ShopUnlockSpec.java`, tested by `ShopUnlockSpecTest` and wired in `TestHooks`; the one bundled use is `quests/ignis_ashes.json` (`"shop_unlock": {"category": "dragon_eggs"}`).
- Lib has no persistent per-player key store. `SuperiorGrantApi` aggregates grants from providers at query time and stores nothing; `RequirementEvaluationApi` evaluates equipment-oriented requirements. No Superior addon, KubeJS script, or installed mod gates crafting recipes per player. Lib already has mixin configs (`superior_lib.mixins.json`), a network channel (`network/SuperiorLibNetwork.java`), and `SavedData` stores.
- Vanilla `CraftingMenu.slotChangedCraftingGrid` computes the crafting result on the server only (the player inventory grid uses it too). The smithing table's `createResult` also runs on the client, so a smithing lock needs the player's keys on the client.
- Shop entry builders have `requiresUnlock`, `lockDisplay`, `lockHint` (`ShopKubeJsContracts.java:578-593`). `items.js` forwards lock fields only for whole categories (`SHOP_CATEGORY_LOCKS`, line 4634); `applyTradeEntry` (line 4561) does not forward entry-level lock fields. `items.js` entry IDs default to a hash of the entry's fields (`defaultTradeEntryId`, line 4537). `SHOP_ITEMS.fishing` (near line 2696) sells Tide gear, including four IDs Tide 2.1.1 does not register (`tide:iron_fishing_hook`, `tide:lavaproof_fishing_hook`, `tide:braided_line`, `tide:reinforced_line`). `config/superior_shop/shop_catalog.json` is a generated snapshot and is not authored.
- Sea creature collection: `SHOP_ITEMS.collection_sea_creatures` (line 2971) is a sell category of 220 hand-written entries, routed into five rarity sub-categories by `hoverTheme` colour (`routeShopCategory`, line 4525); prices follow colour exactly (gray 50, green 100, blue 150, purple 200, yellow 400). Its colours disagree with Starcatcher's rarity for 33 of the 198 Starcatcher fish it lists (for example Stonefish is common in the Shop and legendary in Starcatcher); it lists 10 fish from `stardew_fishing`, which is not installed, and misses 43 fish Starcatcher can catch.
- Starcatcher stores a fish's rarity only on a caught stack (`SCDataComponents.CAUGHT_FISH_INFO`, read by the client `GetNameMixin`), not on the item, so rarity must come from the fish registry. Every non-trash Starcatcher guide fish except the creeper head and nether star catches is in `#minecraft:fishes` (Tide adds `#tide:fish`, Starcatcher adds its own tag).
- Shop entry colours are parsed to RGB when the catalog is built (`EntryBuilder.hoverTheme`, `ShopKubeJsContracts.java:557`, `ColorParseUtil.parseRgbLoose`). Shop builds per loader (`forge1201`, `neoforge1211`); optional third-party jars are pinned through `verifyOptionalPinnedFile` (`forge1201/build.gradle:76`). The KubeJS `SuperiorShop` binding is `kubejs/SuperiorShopBindings.java`, bound in `forge1201/src/main/java/com/superior/shop/kubejs/SuperiorShopKubePlugin.java:42`. The Shop runs its KubeJS catalog events after server start (`SuperiorShopMod.reloadFromKubeJsEvents`), when datapack registries are loaded.

## 3. Binding decisions

- D1. Starcatcher is the only fishing system. Tide remains fish content and non-gear items. Story reads fish data from Starcatcher only; no Story code names a fish, biome, or rarity list.
- D2. Tide gear retirement is implemented in Superior Tweaks as resource overrides, Forge event listeners, and the Tweaks EMI plugin. Not KubeJS, not another Superior mod. The Tide config change is a runtime config edit.
- D3. A quest names an optional `location`. Each location kind is a module registered through `StoryHooks`; built in: `structure` (ID, `ns:*`, `#tag`, or array), `pool` (pool ID or array), `biome` (ID, `#tag`, or array). The quest `structure` field and the `also` objective `structure` field are removed in the same change, with no alias; bundled quests, README, and tests move with it. A quest with no location is a fetch quest: no marker, no visit stage.
- D4. The guide is separate from Bountiful. Guide content lives in its own folders, guide progress is a quest chain (never the `fisherman` reputation that bounties use), and `compat/fishing` never references `compat/bountiful`. The only touch point is one guide choice that jumps to a branch holding the existing `bounty` line, so fisherman villagers still offer bounties.
- D5. Fish hints are Starcatcher's own restriction descriptions, rendered on the client in the player's language (Story sends a marker, the client calls `getDescription` in guide context, as Starcatcher's guide does). Mod language files are not loaded on a dedicated server, so hints must not be resolved server-side.
- D6. Each quest file chooses its fish mix: `kinds` distinct species (default 1, at most 3) and `quantity` of each (default 1, at most 16). Picks prefer entries the player has not caught (Starcatcher's guide record), fall back to any eligible entry, are distinct within a quest, and are stored in the player's quest instance.
- D7. The tackle ladder is Starcatcher gear. Each tier's reward unlocks the fluid of the next tier: the Amethyst Hook (lava) before lava fish, the Cloud Bobber (open air) before void fish. Rod skins are cosmetic milestone rewards.
- D8. One key per tier, `fishing_tier_<n>`, granted only by that tier's final quest reward, opens both the tier's Shop stock and the crafting of the tier's gear. Shop entries show as teasers with a hint; recipe locks show a hint when the player tries the recipe. The reward uses `notify: false`; the fisherman announces what opened in the turn-in dialogue.
- D9. Superior Lib owns per-player unlock keys (operator decision). Why Lib, per its admission contract: the same per-player state now has two consumers (Shop gates, recipe locks) and a writer (Story), recipe gating must work without the Shop installed, and the existing owner (Shop) is a consumer module. The store is moved, not duplicated: Shop's store, saved data, and unlock subcommands are deleted. Lib owns the store, `superior_lib_unlocks` saved data, a change event, client sync of the player's own keys, and the operator command. Lib assigns no meaning to keys; `category:` / `entry:` remain Shop vocabulary. Shop keeps its gates, its operator bypass, its presentation, and its notice (shown only when a changed key gates at least one Shop target). Existing Shop unlock saves are not carried over (pre-release, no migrations).
- D10. Superior Lib owns recipe locks: data files that say which recipes need which keys. A locked recipe produces no result in the vanilla crafting grid (player inventory, crafting table, and every menu that uses `CraftingMenu.slotChangedCraftingGrid`) or the smithing table until the player holds every required key; the player sees the lock's hint on the action bar. Recipe viewers still list locked recipes, so players see what an unlock gives. Other crafting surfaces are not gated.
- D11. Story's unlock modules become core `unlock`, `lock`, and `unlocked`, backed by Lib, usable wherever actions and conditions are accepted. `shop_unlock`, `shop_lock`, `shop_unlocked`, `compat/shop/ShopUnlockBridge.java`, and `ShopUnlockSpec.java` are removed with no alias; bundled content, tests, and docs move in the same change. The value forms stay: a key string, `{"category": id}` / `{"entry": id}` (written as Shop's automatic keys), `key`, `keys`, `notify`. `coins` stays in `compat/shop`.
- D12. The Shop knows item rarity. Superior Shop owns a five-value rarity vocabulary, a list of rarity sources, a Starcatcher source, a `hoverTheme: 'auto'` option that colours an entry by its rarity (falling back to the item's vanilla rarity), and a KubeJS `SuperiorShop.rarity(item)` lookup. Starcatcher knowledge lives only in that source; scripts never call Starcatcher. The sea creature collection is then derived, not authored: its fish are the `#minecraft:fishes` items with a known rarity, and their sub-category, colour, and sell price follow that rarity, so a mod that adds Starcatcher fish appears with no Shop authoring. Selling each rarity is gated on the matching tier key. Only sea creatures that are not Starcatcher fish are listed by hand. Story's fish source reads the same Starcatcher registry, so "common" means the same fish to the fisherman and the Shop.

## 4. JSON surface (target)

Quest locations:

```json
{"location": {"structure": "cataclysm:burning_arena"}, "item": "cataclysm:burning_ashes"}
{"location": {"biome": "#minecraft:is_ocean"}, "item": "minecraft:cod"}
{"location": {"pool": "superiorstory:safe_outposts"}}
{"item": "minecraft:cod"}
```

`also` entries take the same `location` object. `entity` still defaults to the structure profile's first boss when the location is one exact structure. Kill credit: a structure location keeps its bounding box plus 32 blocks; a biome location credits a kill inside a matching biome; a pool behaves like its structures.

Fish quests (item source `fish`, loaded only with Starcatcher). `quests/guides/fishing/rare_3.json`, the last quest of tier 3:

```json
{"mods": ["starcatcher"],
 "item": {"fish": {"rarity": "rare", "dimension": "minecraft:overworld", "fluid": "minecraft:water", "kinds": 3}},
 "if": {"quest": {"id": "superiorstory:guides/fishing/rare_2", "stage": "turned_in"}},
 "reward": {"give": [{"item": "starcatcher:heavy_hook"}, {"item": "starcatcher:amethyst_hook"}],
            "unlock": {"key": "fishing_tier_3", "notify": false}}}
```

- `fish` fields, all optional: `rarity` (`common`, `uncommon`, `rare`, `epic`, `legendary`, or an array; `trash` is never offered), `dimension` (selector, matched against the dimensions the entry's dimension restriction accepts), `fluid` (selector over `minecraft:water`, `minecraft:lava`, `minecraft:empty`), `kinds` (1-3), `quantity` (1-16, per kind), `new` (default `true`). Only guide fish with `catch_info` type fish count. Out-of-range values are load errors.
- Turn-in needs every kind in its quantity (the existing all-required item-array rule); the `collected` stage is reached when the player carries all of them.
- With no `location`, each kind whose entry has a biome restriction gets its own derived `biome` marker, placed through the same per-objective marker path `also` objectives use; a kind without one has no marker. An explicit `location` replaces all derived markers.
- When fewer eligible entries exist than `kinds`, the quest is blocked with its own reason (`superiorstory.blocked.no_fish`).
- `mods` on a quest file means what it means on a dialogue: the file is skipped silently when a named mod is absent.

Fish facts, filled in any scene that carries a fish quest: `{fish}` (all picked fish with counts, rarity colour, hover), and for each kind `n` (1-3) `{fish_n}`, `{fish_n_rarity}`, `{fish_n_where}` (biomes), `{fish_n_dimension}`, `{fish_n_depth}` (elevation), `{fish_n_fluid}`, `{fish_n_when}` (daytime, weather, moon phase), `{fish_n_bait}`, `{fish_n_more}` (every other restriction, including types added by other mods), `{fish_n_gear}` (items whose `starcatcher:modifiers` entry enables the fish's fluid: `survives_lava` for lava, `no_gravity` for `minecraft:empty`; at most two, as `@item:` names). The unnumbered `{fish_rarity}`, `{fish_where}`, and so on are kind 1. A line with `needs` on an unset fact is skipped, so a scene written for three kinds reads correctly for one.

Unlocks (Story core, backed by Lib):

```json
{"unlock": "fishing_tier_1"}
{"unlock": {"key": "fishing_tier_1", "notify": false}}
{"unlock": [{"category": "dragon_eggs"}, "ignis_wares"]}
{"lock": "fishing_tier_1"}
{"if": {"unlocked": "fishing_tier_1"}}
```

`notify` (default true) lets Lib's change event reach consumers with the notice flag set; the Shop prints its notice only for keys that gate a Shop target, so a recipe-only key never prints a Shop line.

Recipe locks (Lib data, `data/<ns>/superior_lib/recipe_locks/<name>.json`):

```json
{"requires": "fishing_tier_2",
 "results": ["starcatcher:steady_bobber", "starcatcher:stone_hook", "starcatcher:humble_rod"],
 "hint": {"translate": "superiorstory.guide.fishing.locked.tier_2"}}
```

- `requires`: a key or array (every key needed). `results`: item selectors (ID, `ns:*`, `#tag`); every crafting or smithing recipe whose result matches is locked. `recipes`: recipe IDs, for locking one recipe among several with the same result. At least one of `results` / `recipes`. `hint`: optional text or `{"translate": key}`.
- Several lock files may name the same recipe; the player needs every file's keys. Unknown fields are load errors; a result or recipe ID that matches nothing is a warning naming the file.

`profession` condition: `{"if": {"profession": "fisherman"}}` (ID, default namespace `minecraft`, or array), true when the speaking entity is a villager with that profession.

## 5. Requirements and completion matrix

"Deployed" means the reobfuscated jar is in `mods/` with SHA-256 parity (section 10); no row has been exercised in game. "Load" is `everyBundledDialogueAndProfileLoads`.

| ID | Requirement | Owner | Implementation | Production path | Validation | Delivery |
|---|---|---|---|---|---|---|
| R1 | Quest `location` modules `structure`, `pool`, `biome`; `structure` field removed from quests and `also` | Story | done (`server/QuestLocations`, 8 quest files migrated) | deployed, not exercised | `StructureQuestTest` (10), load | deployed |
| R2 | Biome location: search, arrival, marker, kill credit, dimension filter | Story, Locator API | done | deployed, not exercised | compile only; in game pending | deployed |
| R3 | Location optional (fetch quests) | Story | done | deployed, not exercised | `StructureQuestTest`, load | deployed |
| R4 | `profession` condition, shared with `BountifulBridge` | Story | done | deployed, not exercised | load | deployed |
| R5 | Item-source module kind; Starcatcher `fish` source with filters, `kinds`, `quantity`, picks stored per instance | Story `compat/fishing` | done | deployed, not exercised | `FishSelectionTest` (2) | deployed |
| R6 | Fish facts per kind, client-rendered restriction hints, derived gear hint, marker per kind | Story `compat/fishing` | done (`@fishinfo:` in `Vars`, `client/ValueKinds`) | deployed, not exercised | in game pending | deployed |
| R7 | `mods` on quest files | Story | done | deployed | load | deployed |
| R8 | Lib unlock key store, change event, client sync, operator command | Superior Lib | done (`api/unlock`, `unlock/`, `command/UnlockCommands`, network `72`) | deployed, not exercised | compile; in game pending | deployed |
| R9 | Shop reads Lib keys; Shop store, saved data, and unlock subcommands removed; notice only for Shop-gating keys | Superior Shop | done (`platform/UnlockStore`, Forge `LibUnlockBridge`) | deployed (Forge 1.20.1), not exercised | `ShopUnlockGatingTest` (4) | deployed Forge; NeoForge not built (section 10) |
| R10 | Story `unlock` / `lock` / `unlocked` replace the `shop_*` modules; `ignis_ashes` migrated | Story | done (`dialogue/UnlockSpec`, `ShopUnlockBridge` deleted) | deployed, not exercised | `UnlockSpecTest` (2), load | deployed |
| R11 | Recipe locks: data loader, crafting grid and smithing enforcement, hint | Superior Lib | done (`unlock/RecipeLocks`, `mixin/inventory/*`) | deployed, not exercised | `RecipeLocksTest` (2) | deployed |
| R12 | Fisherman guide scene, line pools, five tier chains with per-quest fish mix, rewards, bounty touch point | Story content | done (15 chain + 5 repeatable quests) | deployed, not exercised | load | deployed |
| R13 | Guide recipe lock files, one per tier | Story content | done (5 files) | deployed, not exercised | in game pending (unmatched selectors warn in the log) | deployed |
| R14 | Shop `fishing` category restocked with Starcatcher tackle; tier entries gated by `fishing_tier_<n>` | Shop content (`items.js`) | done | runtime script, not exercised | in game pending | runtime workspace |
| R19 | Sea creature collection derived (fish from `#minecraft:fishes` with a known rarity; sub-category, colour, price from rarity); rarity sub-categories gated by `fishing_tier_<n>` | Shop content (`items.js`) | done | runtime script, not exercised | in game pending | runtime workspace |
| R20 | Shop rarity vocabulary, rarity sources, Starcatcher source, `hoverTheme: 'auto'`, KubeJS `SuperiorShop.rarity` | Superior Shop | done (`api/shop/ShopRarities`, Forge `integration/starcatcher`) | deployed (Forge 1.20.1), not exercised | `ShopRaritiesTest` (1) | deployed Forge |
| R15 | Tide gear recipes, loot modifiers, fisherman trade, code-added drops, advancements retired; retired items hidden in EMI | Superior Tweaks | done (59 recipe, 3 loot modifier, 12 advancement overrides; `compat/tide/TideGearRetirement`) | deployed, not exercised | compile; in game pending | deployed |
| R16 | Tide config: vanilla rod not overridden, no journal given | runtime config | done | live config | source review | runtime workspace |
| R17 | README, AGENTS, Lib primitive guide, Shop API doc, DOC_MAP current | Story, Lib, Shop, root | done | n/a | n/a | committed with the code |
| R18 | Operator in-game acceptance (section 9) | operator | n/a | n/a | pending | pending |

Rows move to PASS only with the evidence named in their batch. Deferred, blocked, and unrun are not PASS.

## 6. Batches

Dependencies: B1, B2, B3, and B6a are independent. B4 depends on B3. B5 depends on B1. B6 depends on B3, B4, B5, and (for the sea creature collection) B6a. B7 closes all.

### B1 Location modules (Story)

- [x] Add a location kind to `api/StoryHooks.java` (`registerLocation`, beside `registerCondition` at line 205). A compiled location answers: can it exist in this dimension, start a search (a `LocateLine.Search`-style job with a sink), has the player arrived, marker position, kill-credit test, and display name.
- [x] `structure` and `pool`: move today's behavior behind the module. `dialogue/QuestDef.java` (`structure is required` at line 66, `Target` at line 32, `objective` at line 114), `server/StoryQuests.java` (`VISIT_RANGE` 62, arrival 626, `boundsOf` 726, instance `id` 444), `server/QuestObjectives.java` (`resolve` 151, `inside` 143, `bossDied` 292), and `server/LocateLine.java` (`LocatorQuery.StructureQuery` at 197). The known-structure index stays structure-only.
- [x] `biome`: search through Superior Locator's biome query (`com.superior.locator.api.LocatorQuery`, `LocatorQueryKind.BIOME` at line 71) with the same silent ANY-mode session attributes structure searches use; arrival and kill credit when the player or kill position is in a matching biome; dimension filter from the level's biome source `possibleBiomes()`; marker at the found position.
- [x] No location: skip search, marker, and visit; `collected` and turn-in work as today.
- [x] Rename across content in one change: the 8 bundled quest files (13 `structure` uses), `api/StoryQuestEvent.java` (`structureId` becomes the location ID plus kind; only `StoryWaypoints` and `StoryQuests` consume it), README sections "Mini quests" and "Extra objectives".

Sufficient evidence: `QuestDef` parse test covers each location form, the rejected old `structure` key, and a fetch quest; the existing `everyBundledDialogueAndProfileLoads` passes with migrated content; compile.

### B2 Retire Tide fishing gear (Superior Tweaks + runtime config)

- [x] `superior_tweaks/src/main/resources/META-INF/mods.toml`: optional `tide` dependency with `ordering="AFTER"` (as for `mutantmore`, lines 28-33) so Tweaks data overrides win.
- [x] Tag `data/superior_tweaks/tags/items/retired_tide_fishing_gear.json`: the `tide:` members of `#tide:fishing_rods` (13 rods; not `minecraft:fishing_rod`), `#tide:hooks` (6), `#tide:lines` (5), `#tide:bobbers` (33), `#tide:bait_items` (5), plus `tide:fishing_journal`, `tide:fish_finder`, `tide:angling_table` (65 items).
- [x] Recipe overrides `data/tide/recipes/<file>.json` = `{"conditions":[{"type":"forge:false"}]}` for the 59 files producing tagged items: `stone_fishing_rod`, `iron_rod_smithing`, `gold_rod_smithing`, `crystal_rod_smithing`, `diamond_rod_smithing`, `netherite_rod_smithing`, `midas_rod_smithing`, `fishing_hook`, `fiery_hook`, `permafrost_hook`, `twilight_hook`, `lavaproof_hook`, `void_hook`, `fishing_line`, `copper_line`, `iron_line`, `golden_line`, `diamond_line`, `bait`, `lucky_bait`, `magnetic_bait`, `abyss_bait_from_clubfish`, `incandescent_bait_from_larva`, `fishing_journal`, `fish_finder`, `angling_table`, and all 33 `bobbers/*.json`.
- [x] Loot modifier overrides `data/tide/loot_modifiers/add_{blazing,echo,prismarine}_fishing_rod.json` keep their type with a never-true condition (`minecraft:random_chance` 0). The shared `data/forge/loot_modifiers/global_loot_modifiers.json` is not replaced.
- [x] Trade: a `VillagerTradesEvent` listener in `events/SuperiorTweaksForgeEvents.java` at `EventPriority.LOWEST` removes, for `VillagerProfession.FISHERMAN`, every listing whose class name starts with `com.li64.tide.` (Tide adds only the Village Fishing Rod there). Loaded only when Tide is present.
- [x] Code-added drops: Tide's `BeehiveBlockMixin` drops a `tide:honeycomb_fishing_rod` on 5% of honeycomb harvests. The same Tweaks listener class cancels `EntityJoinLevelEvent` for an `ItemEntity` whose stack is in `#superior_tweaks:retired_tide_fishing_gear`, which also covers any other code path that drops retired gear.
- [x] Advancements: override all 12 `data/tide/advancements/*.json` with `{"conditions":[{"type":"forge:false"}]}` so the unreachable Tide tab disappears as a whole (no orphaned children).
- [x] EMI: `compat/emi/SuperiorTweaksEmiPlugin.java` removes stacks in `#superior_tweaks:retired_tide_fishing_gear` (JEI is disabled in this instance).
- [x] Runtime config `config/tide/tide_server.json`: `"overrideVanillaRod": false`, `"giveJournal": false`. Starcatcher `give_rod` stays true: a vanilla-rod catch is the entry into Starcatcher.

Sufficient evidence: the recipe audit (every Tide recipe whose result is tagged has an override, and no override targets a kept result) run against the built jar; Tweaks build; jar contains the overrides; config values present. Loading without errors is part of operator acceptance.

### B3 Shared unlock keys (Superior Lib, Superior Shop, Story)

First milestone: the existing `ignis_ashes` reward, rewritten as `"unlock": {"category": "dragon_eggs"}`, writes the key through Lib and opens the Shop's `dragon_eggs` teaser category (the production path that works today, moved).

- [x] Lib `api/unlock/PlayerUnlockApi` (exposed through `SuperiorLibApi`): `unlock(player, key, sourceId, notify)`, `lock(player, key)`, `isUnlocked(player, key)`, `keys(player)`. Keys are trimmed, non-blank strings; Lib gives them no meaning.
- [x] Lib `SavedData` store `superior_lib_unlocks` (overworld data storage, per player UUID), modelled on Shop's `PlayerUnlocksSavedData`.
- [x] Lib `PlayerUnlockChangedEvent` (player, key, added or removed, sourceId, notify) on the Forge bus after each real change.
- [x] Lib client sync on `SuperiorLibNetwork`: the player's own full key set on login and after each change; a client-side `isUnlocked` for the smithing lock (B4).
- [x] Lib operator command `/superior_lib unlock|lock <players> <key>` and `/superior_lib unlocks <player>` (list).
- [x] Shop: add a required `superior_lib` dependency (`mods.toml`, `build.gradle`); `UnlockAccess` reads Lib (operator bypass stays in Shop); delete `UnlockService`, `PlayerUnlocksSavedData`, and the `unlock` / `lock` subcommands of `/superior_shop`; `SuperiorShopServices` and KubeJS `SuperiorShop.unlock/lock/isUnlocked` call Lib; a `PlayerUnlockChangedEvent` listener re-syncs the player's shop and, when `notify` is set and the key gates at least one Shop target, prints `superior_shop.unlock.notice`.
- [x] Story: core `unlock`, `lock`, `unlocked` in `module/CoreModules.java` calling Lib; move `ShopUnlockSpec` parsing into core (renamed `UnlockSpec`); delete `compat/shop/ShopUnlockBridge.java`; rename `ShopUnlockSpecTest` and update `TestHooks`; migrate `quests/ignis_ashes.json`; `coins` stays in `compat/shop`.

Sufficient evidence: Story compile and the renamed spec test; Lib and Shop compile; Shop's existing gate tests (if any reference `UnlockService`) updated and passing. The dragon eggs unlock in game is operator acceptance.

### B4 Recipe locks (Superior Lib)

First milestone: one lock file gating one Starcatcher crafting recipe, opened with `/superior_lib unlock`.

- [x] Loader for `data/<ns>/superior_lib/recipe_locks/*.json` on datapack reload: parse once, reject unknown fields with file and field named, keep valid siblings, resolve `results` / `recipes` against the loaded recipe manager into one `recipe ID -> required keys` map (keys from every matching file, all required). Warn for selectors or IDs that match nothing.
- [x] Crafting grid: a mixin at the point `CraftingMenu.slotChangedCraftingGrid` sets the result, clearing it when the matched recipe's ID is locked for that player (server side; the player inventory grid shares the method).
- [x] Smithing table: a mixin on the smithing menu's result creation clearing the result for a locked recipe, on both sides (client reads the synced key set from B3).
- [x] Hint: when a locked recipe is matched on the server, show its `hint` on the player's action bar, at most once per recipe per few seconds.
- [x] The lock map is sent to clients with the key set, only the smithing entries being needed client-side; keep the payload to recipe IDs and keys.
- [x] Update `docs/PRIMITIVE_USAGE_GUIDE.md` with `PlayerUnlockApi` and recipe locks (Lib rule).

Sufficient evidence: compile; one focused Lib test for lock resolution (a result selector and a recipe ID resolve to the right recipes; two files on one recipe require both keys). Crafting behavior is operator acceptance.

### B5 Fishing compat and shared conditions (Story)

First milestone (the production connection before breadth): one `common` fish quest offered by a real fisherman villager, a fish picked from the live Starcatcher registry, its hints rendered on the client, marker to the derived biome, hand-over, reward. Then complete the filters, `kinds`, `quantity`, and facts.

- [x] `profession` condition in `module/CoreModules.java` (beside `biome` at line 72), with the profession lookup shared by `compat/bountiful/BountifulBridge.java:196`.
- [x] Item-source kind in `StoryHooks` (`registerItemSource`); the plain item spec is the built-in source; `QuestDef` stores a source instead of an exact `ItemSpec` for `item`; the chosen items (a list, each with a quantity) live in the instance tag and every reader of the quest item (`server/QuestDefinitions.java`, `hand_over {"quest"}`, `has_item {"quest"}`, the `collected` stage, fact filling) reads the instance items. `QuestDropsModifier` ignores quests without an entity, as today.
- [x] `compat/fishing` (registered only when `starcatcher` is loaded; compileOnly Starcatcher jar through `workspaceResolveExternalJar` in `build.gradle`, as Bountiful at lines 35 and 55): a catalogue of guide fish built from `FishApi.getFishes(level)` at server start and datapack reload (rarity, item, accepted dimensions via `dimension_entries`, fluids, biome entries or tags); the `fish` source with D6 selection over `FishingGuideAttachment.getFishesCaught`; derived biome markers per kind through the `QuestObjectives` marker path; the no-fish blocked reason.
- [x] Fish facts: a `VarSource` fills the per-kind names, rarity, and gear facts server-side and puts a `@fishinfo:<entry>:<part>` marker in the restriction-backed facts; `dialogue/Vars.java` gains the marker next to `@item:`/`@entity:` (line 21), and the client resolver calls `getDescription` on the synced registry entry in guide context. Parts group restriction types (`where`, `dimension`, `depth`, `fluid`, `when`, `bait`, `more`); a value that would pass `Vars.MAX_VALUE` keeps whole leading parts. Check `Vars.MAX_VARS` (32) against three kinds of facts plus quest facts; raise it if needed.
- [x] `mods` on quest files: add to `QuestDef` fields and skip silently as `Dialogue.java:220` does.
- [x] Lang keys for every new player-facing string in `assets/superiorstory/lang/en_us.json`.

Sufficient evidence: compile; one focused test that the `fish` source's filters, `kinds` (distinct), and `quantity` select correctly from a small in-memory catalogue; the first milestone content loads. Hint rendering and in-world behavior are operator acceptance.

### B6a Shop item rarity (Superior Shop)

First milestone: `SuperiorShop.rarity('starcatcher:stonefish')` returns `legendary` from a script, and an entry with `hoverTheme: 'auto'` for it shows the legendary colour.

- [x] Rarity vocabulary owned by Shop: `common`, `uncommon`, `rare`, `epic`, `legendary`, each mapped to the Shop colour names the catalog already uses (gray, green, blue, purple, yellow, resolved through `ColorParseUtil`).
- [x] Rarity sources: a small Shop API (`api/shop`) where a source registers `item ID -> optional rarity`; sources are asked in registration order and the first answer wins. Loader-neutral, in `src/main`.
- [x] Starcatcher source in the Forge 1.20.1 loader module (`forge1201`, under `integration/starcatcher`), registered only when `starcatcher` is loaded; compileOnly Starcatcher jar through `verifyOptionalPinnedFile`, as the optional Eternal Currencies jar (`forge1201/build.gradle:76`). It builds an `item -> rarity` map from `FishApi.getFishes(server.registryAccess())` (fish-type entries) with `hasGuideEntry()` true, mapping Starcatcher's common through legendary one to one; `trash` and `none` give no rarity. The map is rebuilt whenever the catalog is rebuilt, never per lookup.
- [x] `hoverTheme: 'auto'` in the KubeJS entry builder (`kubejs/contract/ShopKubeJsContracts.java`, `EntryBuilder.hoverTheme` at line 557) and in the authored JSON base: the Shop resolves the entry item's rarity when the catalog is built and stores that colour; with no source answer it uses the item's vanilla rarity (common, uncommon, rare, epic); the GUI editor shows the stored colour as today.
- [x] KubeJS binding `SuperiorShop.rarity(itemId)` on `kubejs/SuperiorShopBindings.java` (bound in `forge1201/.../SuperiorShopKubePlugin.java:42`): the rarity ID from the registered sources, or `null`. It does not fall back to vanilla rarity, so scripts can tell "known rarity" from "no rarity".

Sufficient evidence: Shop build; one focused test of rarity resolution order (a registered source answers first; `auto` falls back to vanilla rarity; `SuperiorShop.rarity` returns null without a source answer).

### B6 Fisherman guide content (Story data, recipe locks, Shop stock)

Story data:

- [x] `scenes/guides/fisherman.json`: `"npc": "minecraft:villager"`, `"if": {"profession": "fisherman"}`, `"mods": ["starcatcher"]`, higher `priority` than `bounty_villager`. Branches: offer the next available quest, react to carried quest fish (`hand_over {"quest"}`), explain each kind's hints with `needs`-guarded lines (`fish_2`, `fish_3`), announce each tier's unlock after turn-in (one line per tier, guarded by `unlocked` and that tier's quest stage, naming the new stock with `{item:...}`), and one "Got any work?" choice that jumps to a branch with `{"bounty": {}}`.
- [x] Line pools under `line_pools/guides/fisherman_*.json` for greetings, praise, and hint framing.
- [x] Quests `quests/guides/fishing/<tier>_<n>.json`, three per tier, each chained on the previous `turned_in`. The first two quests of a tier pay coins; the third pays the tier reward plus `"unlock": {"key": "fishing_tier_<n>", "notify": false}`. Default ladder (content, adjustable without code):

| Tier | Fish filter | Quests 1 / 2 / 3 (kinds x quantity) | Tier reward (Starcatcher) | Key |
|---|---|---|---|---|
| 1 | common, overworld, water | 1x1 / 1x1 / 1x2 | Copper Hook, Worms | `fishing_tier_1` |
| 2 | uncommon, overworld, water | 1x1 / 2x1 / 2x1 | Steady Bobber, Stone Hook, Humble skin template | `fishing_tier_2` |
| 3 | rare, overworld, water | 1x1 / 2x1 / 3x1 | Heavy Hook, Clear Bobber, Seeking Worm, Amethyst Hook | `fishing_tier_3` |
| 4 | epic, overworld or nether, water or lava | 1x1 / 1x1 / 2x1 | Golden Bobber, Meteorological Bait, Cloud Bobber | `fishing_tier_4` |
| 5 | legendary, any dimension or fluid | 1x1 / 1x1 / 1x1 | Legendary Bait, Sky skin template | `fishing_tier_5` |

- [x] One repeatable quest per completed tier (`repeatable`, `cooldown` `1d`, same filter, 1x1) paying coins, so the guide stays useful after the ladder.
- [x] Recipe locks `data/superiorstory/superior_lib/recipe_locks/fishing_tier_<n>.json`, one per tier: `requires` that tier's key, `results` that tier's Shop stock (B6 Shop table) plus the skinned rods of that tier's templates (tier 2 `humble_rod`, `bamboo_rod`; tier 5 `sky_rod`, `magmaforged_rod`), and a translated `hint` naming what the fisherman wants. Items with no recipe need no entry (a result without recipes only warns). Starter tackle is never locked.
- [x] Validate every item ID against the installed Starcatcher jar.

Shop stock (runtime workspace `kubejs/server_scripts/Integrations/superior_shop/items.js`, the Shop catalog source; never `config/superior_shop/shop_catalog.json`):

- [x] `applyTradeEntry` (line 4561) forwards entry-level gates, entry value first, then category `defaults`: `requiresUnlock`, `lockDisplay`, `lockHint` to the entry builder. Generic; no Shop code.
- [x] Replace every `SHOP_ITEMS.fishing` entry (all Tide items, including the four unregistered IDs) with Starcatcher entries. Category `defaults` add `lockDisplay: 'teaser'`. Every entry gets an explicit `id` of the form `fishing_<item path>`. The first entry is `starcatcher:starcatcher_rod` (it becomes the category icon). The `fishing` category itself stays open.
- [x] Stock and gates (default content; prices are the default band, tuned freely):

| Gate (`requiresUnlock`) | Granted by | Entries | Price band (coins) | `lockHint` |
|---|---|---|---|---|
| none (open) | n/a | `starcatcher_rod`, `starcatcher_guide`, `hook`, `bobber`, `worm` (quantity 8), `starcatcher_twine`, `tackle_box` | 5-50 | none |
| `fishing_tier_1` | `common_3` reward | `copper_hook`, `leaf_bobber`, `cherry_bait`, `gunpowder_bait` | 50-80 | Help the fisherman with common fish. |
| `fishing_tier_2` | `uncommon_3` reward | `steady_bobber`, `stone_hook`, `mossy_hook`, `lush_bait`, `murkwater_bait`, `dripstone_bait`, `humble_skin_smithing_template`, `bamboo_skin_smithing_template` | 100-150 | Help the fisherman with uncommon fish. |
| `fishing_tier_3` | `rare_3` reward | `heavy_hook`, `clear_bobber`, `glowing_bobber`, `split_hook`, `seeking_worm`, `amethyst_hook`, `fish_radar` | 200-300 | Help the fisherman with rare fish. |
| `fishing_tier_4` | `epic_3` reward | `golden_bobber`, `gold_hook`, `echoing_hook`, `frozen_hook`, `sculk_bait`, `meteorological_bait`, `cloud_bobber` | 400-600 | Help the fisherman with epic fish. |
| `fishing_tier_5` | `legendary_3` reward | `legendary_bait`, `shiny_hook`, `almighty_worm`, `sky_skin_smithing_template`, `magmaforged_skin_smithing_template` | 800-1200 | Help the fisherman with legendary fish. |

  Each tier's stock includes the items its quest reward hands out, so a lost or used reward can be bought again. All 38 item IDs are `starcatcher:` IDs verified present in the 3.1.4.6 jar (item models). GUI catalog overrides keyed by the old hashed IDs become inert (pre-release, no migration).

Sea creature collection (same file, D12; needs B6a):

- [x] Replace the 220 hand-written `SHOP_ITEMS.collection_sea_creatures` entries with a list built during the Shop's KubeJS catalog events: every item in `#minecraft:fishes` (KubeJS's ordinary tag lookup) for which `SuperiorShop.rarity(item)` returns a rarity, one entry per item. Tide and Starcatcher both add their fish to that tag and other fish mods usually do. Only Starcatcher fish get a rarity from a registered source (B6a), so cooked fish, Starcatcher's creeper head and nether star catches, and trash (clam, conch) are left out without a list. The script calls no Starcatcher class.
- [x] One five-row table maps the returned rarity to sub-category and price: common 50, uncommon 100, rare 150, epic 200, legendary 400 (today's prices per colour, which match exactly). Every entry uses `hoverTheme: 'auto'`, so the Shop colours it. Each entry keeps the category defaults (sell, the 0.5 multiply `scalingPrice`, `entryTag: 'flash_abyssal_buyback'`) and gets an explicit `id` `sea_creature_<namespace>_<path>`. Order by rarity, then item name. `routeShopCategory` (line 4525) routes by rarity instead of `hoverTheme`.
- [x] `extras`: a short authored list for sea creatures that are not Starcatcher fish, placed in common with `hoverTheme: 'auto'` (vanilla rarity colour). Default: `alexscaves:lanternfish`, `alexscaves:radgill`, `alexscaves:tripodfish`, `alexsmobs:blobfish`, `alexsmobs:flying_fish`, `aquamirae:spinefish`, the four `blue_skies` fish, `cataclysm:lionfish`, `rats:ratfish`. The 10 `stardew_fishing` entries are dropped (mod not installed). An extras item that the derived list already holds is skipped.
- [x] Rarity gates: `collection_sea_creatures_common` needs `fishing_tier_1`, uncommon `fishing_tier_2`, rare `fishing_tier_3`, epic `fishing_tier_4`, legendary `fishing_tier_5`; teaser display, hint "Help the fisherman with <rarity> fish." `items.js` applies the lock table to routed child categories too (today `superiorShopCategories` locks only top-level categories), and a lock table entry may carry `requiresUnlock` keys as well as the current own-key form. The parent `collection_sea_creatures` stays open.
- [x] With no rarity source installed, the derived list is empty and the category holds `extras` only.

Sufficient evidence: `everyBundledDialogueAndProfileLoads` covers the Story files; a one-off check that every item in the quests, lock files, `fishing` entries, and sea creature `extras` exists in the installed jars, that each `fishing_tier_<n>` key is granted by exactly one quest reward and used by at least one Shop entry, one lock file, and one sea creature rarity gate, and that no Tide gear remains in `SHOP_ITEMS`; the expected derived sea creature set computed from the Starcatcher jar data (guide, non-trash, in `#minecraft:fishes`) for comparison with the per-rarity counts the script logs on the operator's next start.

### B7 Validation, docs, delivery

- [x] Story README: quest `location`, fetch quests, item sources and `fish` (`kinds`, `quantity`), fish facts, `profession`, quest `mods`, `unlock` / `lock` / `unlocked` (replacing the Shop unlock section), the guide.
- [x] Story `AGENTS.md`: the Shop unlock bullet names Lib as the key owner and the core modules.
- [x] Lib `docs/PRIMITIVE_USAGE_GUIDE.md` (B4) and a pointer in Lib `AGENTS.md`.
- [x] Shop `docs/KUBEJS_SHOP_API.md` "Unlock gates": keys live in Lib, the command moved to `/superior_lib`, the notice rule; mark the superseded decisions in `SHOP_QUEST_UNLOCKS_PLAN_2026-09-30.md`. New section for item rarity: the vocabulary, `hoverTheme: 'auto'`, `SuperiorShop.rarity`, and how a mod registers a rarity source.
- [x] One consolidated pass after the implementation batches: Story `gradle --no-daemon test`, Lib and Shop builds with their focused tests, Tweaks build (`--continue` where tasks are independent); fix together; re-run only invalidated checks.
- [x] Deploy the compatible set (Lib, Shop, Story, Tweaks reobfuscated jars) with source/deployed SHA-256 parity; commit per repository; commit the Tide config and `items.js` in the runtime workspace repository.
- [x] Update this matrix.

## 7. Tests (proportional)

Extend the `QuestDef` parse contract test for location forms and the removed key; rename the Shop unlock spec test to the core unlock spec; add one `fish` selection test (filters, distinct kinds, quantity), one Lib recipe-lock resolution test, and one Shop rarity resolution test. Reuse `everyBundledDialogueAndProfileLoads` for all content. No tests per tier, fish, lock file, or override file; the recipe and ID audits are one-off checks recorded as evidence.

## 8. Operator decisions

- O1 (resolved 2026-09-30): replace the Shop `fishing` category's Tide gear with Starcatcher tackle in `items.js`, unlocked as fishing quest rewards.
- O2 (resolved 2026-09-30): per-player unlock keys live in Superior Lib; the same key unlocks Shop stock and recipe locks.
- O3 (resolved 2026-09-30): each quest file chooses its fish mix (one kind, several of one kind, or several kinds).
- O4 (resolved 2026-09-30): selling each rarity in the sea creature collection unlocks with the matching tier, and the collection's fish, rarity, colour, and price are derived instead of authored.
- O5 (resolved 2026-09-30): the Shop itself knows item rarity (rarity sources, a Starcatcher source, `hoverTheme: 'auto'`), instead of the KubeJS script calling Starcatcher.

## 9. Operator acceptance (not run by agents)

Agents never launch a client. Pending in game:

- A fisherman villager opens the guide; "Got any work?" reaches a Bountiful bounty; other villagers still show bounties.
- A one-kind quest names a fish with its biome, depth, fluid, and time hints in the client's language; the marker leads to a matching biome. A three-kind quest lists all three with their hints and one marker per kind; turn-in needs all three.
- Tier 4 lava fish are catchable with the Amethyst Hook; tier 5 void fish with the Cloud Bobber.
- Before a tier's third quest, that tier's tackle shows as locked teasers in the Shop and gives no crafting or smithing result, with the hint on the action bar; after turn-in, both open at once and the fisherman names the new stock (no Shop chat line).
- The Ignis ashes quest still unlocks the Shop's dragon eggs, with the Shop's notice.
- The sea creature collection lists Starcatcher's fish under their Starcatcher rarity (for example Stonefish under legendary), shows each rarity as a locked teaser until its tier is done, and after the common tier accepts common fish for sale; the per-rarity counts in the log match the expected set.
- Tide rods, hooks, lines, bobbers, bait, the journal, the fish finder, and the angling table cannot be crafted, looted, or traded and are hidden in EMI; the Tide advancement tab is gone; the vanilla rod fishes vanilla-style and a first catch yields a Starcatcher rod and guide.
- Existing structure quests (Ignis ashes, twin seals, courier letter) still behave after the location change.

## 10. Current state

2026-09-30: B1-B7 implemented, documented, and deployed; nothing exercised in game (R18 pending).

- Validation: Story `test` 51 passed (includes `FishSelectionTest`, `UnlockSpecTest`, `StructureQuestTest`, bundled load); Lib `RecipeLocksTest` 2 passed; Shop Forge `ShopUnlockGatingTest` 4 and `ShopRaritiesTest` 1 passed; Tweaks compiles. The 42 `starcatcher:` IDs in Story data and `items.js` all exist in the installed Starcatcher 3.1.4.6 jar.
- Deployed with SHA-256 parity: `superior_lib.jar` `f4cdae50…`, `superior_shop-0.1.9.jar` `1bbe8e7b…`, `superior_story.jar` `2f183a8a…`, `superior_tweaks-0.2.6.jar` `c01e038c…`. Lib and Shop must ship together with Story: Story's `unlock` / `unlocked` call `PlayerUnlockApi`, so an older Lib jar breaks them.
- Shop NeoForge 1.21.1 was not built: its pinned jars point at the removed Aeronautical Horizons instance. The Forge jar was built through a scratch settings file that skips evaluating `neoforge1211`; the repository `settings.gradle` is unchanged. NeoForge has no Superior Lib, so keys are never held there.
- Runtime workspace: `config/tide/tide_server.json` and `kubejs/.../superior_shop/items.js` are live on disk.
- Known outside scope: Starcatcher's own Bountiful pools reference `starcatcher:fish_caught`, but the trigger is registered as `starcatcher:minigame_completed`.
