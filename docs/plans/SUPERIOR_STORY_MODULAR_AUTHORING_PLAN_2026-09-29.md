# Superior Story Modular Authoring Plan

Status: active
Doc Type: execution-plan
Owner: `addons/superior_story` (cross-addon batches name their owner)
Authoritative For: the September 29 Story feature set: modular hook registry, item hand-over, block speakers, triggers, time and dimension conditions, structure pools, mini quests, boss kill detection, known-structure index, waypoint and talk-prompt presentation, and boss and item hover presentation
Supersedes: none
Superseded By: none
Last Updated: 2026-09-29 America/Denver

## 1. Outcome, scope, authorization

**Requested outcome.** Superior Story becomes a modular JSON authoring system where conversations, scenes, and quests are written with minimal JSON and every new piece plugs in without rewiring. This plan covers the operator's September 29 list, as revised the same day:

1. Generalized item hand-over: dialogue options that appear only when the player carries the required items, and give them to the speaker when picked. Usable from any conversation, trigger, or quest step. (A separate item input screen is deferred; see Section 7.)
2. JSON mini quests: structure + entity + item; the item is added to the entity's loot table and always drops, quest or not (a rare structure's boss killed early must never block progression); dialogue checks the player's inventory so the player can say they already have it; turning it in pays an item and/or Superior Shop coin reward.
3. Talking to blocks, where a dialogue option can make the player use an item on that block (Cataclysm fire altar + burning ashes spawns Ignis).
4. Dimension detection for dialogue selection, lines, choices, and structure search.
5. Day/night detection for dialogues, lines, and choices, read from Minecraft's own day/night state.
6. Skylines waypoint HUD dimmed unless looked at or near; a quest-fitting marker with subtle animation.
7. Smaller, softer, gray Talk prompt.
8. Waypoints placed correctly relative to terrain: the beacon beam is removed; only the HUD marker remains.
9. Detection of killing the named boss within range of the quest structure, with visible feedback.
10. Boss names colored by tier, with definition-bank hover showing an entity preview.
11. General structure location only where explicitly authored (exactly one bundled villager conversation), backed by structure starts recorded during Surface LOD and real generation instead of an expensive search.
12. Structure pools; searches target a pool or a specific structure only.
13. Every item mentioned or used in a conversation (text, variables, hand-over options, rewards) is hoverable and shows the item's full tooltip, including its definition-bank content.
14. Quests are done once per player by default; JSON can make a quest repeatable, optionally with a cooldown.
15. Proximity-based ally sharing through Superior Allies: allies near a player who accepts a quest get the quest too; allies who contribute to the boss kill get credit; allies near the player who turns in also complete it; every qualifying player gets their own full reward.

**Binding contract.** `superior_story/AGENTS.md` "Modular JSON Authoring Contract". Every item satisfies it: registered module, usable in JSON, defaults for everything, derived values over authored ones, load-time validation.

**Exclusions.** Item input screen (deferred), waypoint beam (removed, not replaced), team-wide quest progress or reward splitting, merging scenes into the module system (deferred), FTB Quests UI, `superior_quests` presentation, Truly Modular, deprecated addons, player-save migrations (pre-release: renames are atomic, no aliases), third-party jars (Cataclysm is used only through its normal block interaction).

**Current authorization.** Implemented and deployed September 29 (Section 8 records what is proven and what is not); operator in-game acceptance is pending. All decisions in Section 4 are settled.

## 2. Current state (evidence, September 29)

Source read at `superior_story` `4e626b7`. (Section 2 describes the state before this plan was implemented.)

- `api/DialogueHooks.java`: two registries (actions, conditions). `Action.run(ServerPlayer, Mob, String)` requires a `Mob` speaker; `Condition.test(ServerPlayer, String)` sees only the player; values are scalar strings (`Dialogue.scalar`), so object values (`{"item":..., "quantity":3}`) are impossible. Actions exist only on choices.
- `dialogue/Dialogue.java`: `locate` is a hard-wired line field; `npc` is the only speaker; lines have `needs` but no `if`/`unless`.
- `server/DialogueServer.java`: session is built around `Mob` (`mobId`, NoAI hold, `face`, `mob.getRandom()`); talk probe/`onTalk` accept entity IDs only. Selection by `priority`, then conditional-first, then ID.
- `client/DialogueClient.java` `target()` accepts only `Mob` under the crosshair; `onRenderGui` draws the prompt 18 px tall with 10 px padding in `StoryChrome.LABEL_HOT` (white).
- `server/DialogueLocator.java`: tiers DIRECT → PROFILED → **WIDE** (every registered structure). WIDE is the automatic general search the operator wants removed. No dimension filtering: PROFILED passes every profiled ID in `ONLY_IDS` regardless of whether the player's dimension can place it.
- `server/StoryQuests.java` `onDeath`: boss kill detection already exists (player within 160 blocks of the death, death within 320 blocks horizontally of the structure origin, stage `ACCEPTED`/`VISITED`, profile boss type). It gives no feedback, uses a fixed radius instead of structure bounds, and has no in-game evidence. Item 9 is "verify, generalize, make visible".
- `server/StoryWaypoints.java` + `client/WaypointReveal.java` + `client/StorySkylines.java`: client-side `SuperiorSkylinesWaypoint.addNamed/removeAt`; no Y, no marker kind.
- Superior Skylines `client/map/SuperiorSkylinesWaypoint.java`: `render(RenderLevelStageEvent)` draws a `BeaconRenderer` beam at `AFTER_BLOCK_ENTITIES` (with shaders on, Skylines LOD terrain is composited separately, so the beam shows through nearer LOD terrain: `screenshots/2026-09-29_08.39.46.png`); the HUD marker is projected at **player eye height + 2** at the waypoint column; `renderMarker` fills an opaque diamond and a `0xD0000000` label box at full opacity always.
- Superior Locator derives structure → dimensions from `ChunkGeneratorStructureState.getPlacementsForStructure` (`runtime/LocatorCatalogService.collectStructureDimensions`); not public API.
- Superior Shop: `SuperiorShopApi.grantCurrency(player, currencyId, amount, sourceId, CurrencyGrantDisplayMode)` returns `boolean`; default currency `SuperiorShopMod.DEFAULT_CURRENCY_ID = "internal:coins"`; display modes `SILENT`, `GAIN_POPUP`.
- Superior Lib: `BossTierApi` (tiers), `SuperiorTooltipClientPresentationApi.queueHover(...)`, `TooltipSubject.Kind` (item, skill node, attribute, custom). No entity subject or entity-preview element.
- Superior Tooltips: `GuiGraphicsTooltipInvocationMixin` intercepts every `GuiGraphics.renderTooltip` call, so an item tooltip drawn from any screen gets full Superior presentation.
- Superior Worldgen V2: `NativeStartLedger` (`platforms/mixin-common/.../integration/skylines/`) resolves accepted structure starts once (structure ID, start chunk, bounding box) for Surface LOD and real generation; LRU-evicted, not persisted.
- Cataclysm 3.16 jar: `cataclysm:altar_of_fire` and `cataclysm:burning_ashes` exist.

## 3. Architecture

### 3.1 One registry, five module kinds

Replace `DialogueHooks` with `api/StoryHooks` (atomic rename; no bridge). Every module registers a key and a **compiler**: `JsonElement → compiled object`, run once at reload. Unknown keys and bad values fail that file only, naming file and field.

| Kind | Used where | Built-ins after this plan |
| --- | --- | --- |
| `Condition` | `if`/`unless` on dialogue, line, choice, trigger | `quest_stage`, `quest`, `time`, `dimension`, `biome`, `has_item`, `ftb_quest` (compat) |
| `Action` | choices, lines (when continued past), triggers, quest turn-in, quest `reward` | `function`, `accept`, `turn_in`, `hand_over`, `use_block`, `give`, `coins` (Shop compat), `open`, `ftb_quest`, `ftb_task` (compat) |
| `LineKind` | a line with that key | `locate` |
| `Speaker` | dialogue root key that binds who/what talks | `npc` (entity selector), `block` |
| `Trigger` | dialogue `trigger` | `talk` (implicit with a speaker), `command`, `dialogue_end`, `quest` |

An action may also contribute a derived visibility requirement: a choice carrying `hand_over` or `use_block` is shown only when the player has the items, with no extra `if` needed.

All modules receive one `StoryContext`: player, level, optional speaker (entity or block position), and session variables. Structure-bound continuation dialogues (`"structure": ...`) stay a separate binding.

Shared parsing primitives:
- `IdSelector` (generalized from `StructureSelector`): exact ID, `ns:*`, `#tag`, for any registry.
- `ItemSpec`, used by every item field (`has_item`, `hand_over`, `use_block`, `give`, quest `item`): short form `"ns:item"` or `"#ns:tag"`; object form `{"item": ..., "quantity": 1, "consume": true}`. `quantity` defaults to 1 everywhere. `consume` defaults to true and matters only where items leave the player (`hand_over`). Several items: an array of specs, all required.

Derived variables are modules (`VarSource`): structure facts (existing `StructureFacts`), `time` (`day`/`night`), `dimension` (cleaned name), `speaker`, quest vars (`item`, `reward`, `boss`). Line `needs` works on any variable.

### 3.2 JSON surface (smallest forms first; every other field defaulted)

**Day/night and dimension.** Conditions work at dialogue, line, and choice level; a failing line is skipped like `needs`.

```json
{"text": "Nobody walks out here this late.", "if": {"time": "night"}}
{"text": "Bright day for a stranger to wander in.", "if": {"time": "day"}}
{"npc": "story_villager", "if": {"dimension": "minecraft:the_nether"}, "beats": ["You should not be here."]}
```

`time` is `day` or `night` and reads Minecraft's own state, `Level.isDay()` / `Level.isNight()`, so mods that change day length, sky darkness, or the day cycle are respected without Story configuration. In fixed-time dimensions (the Nether, the End) Minecraft reports neither, so neither value matches; authors use `dimension` there. There are no tick ranges and no dawn/dusk values. `dimension`: ID, `ns:*`, or an array.

**Hand-over options.** A choice with `hand_over` appears only when the player carries the items, and picking it takes them (unless `consume` is false) before the choice's other actions run:

```json
{"text": "Here, the ashes you asked for.", "hand_over": "cataclysm:burning_ashes"}
{"text": "Three blaze rods, as promised.", "hand_over": {"item": "minecraft:blaze_rod", "quantity": 3}}
{"text": "Show her the key.", "hand_over": {"item": "minecraft:trial_key", "consume": false}}
{"text": "I have what you need.", "hand_over": {"quest": "superiorstory:ignis_ashes"}}
```

The `{"quest": ...}` form takes item and quantity from the quest file, turns the quest in, and grants its reward. It works whether or not the player accepted the quest first (a player who already holds the item skips straight to turn-in), as long as the quest is available or active for that player. Missing items only hide the choice; quest-state problems (cooldown, done, gate) are explained instead (3.6).

**Block speaker and block use (the fire altar):**

```json
{"block": "cataclysm:altar_of_fire",
 "beats": [
   "The altar is cold. Old soot fills its bowl.",
   {"text": "It hungers for something that still burns.",
    "choices": [
      {"text": "Place the burning ashes.", "use_block": "cataclysm:burning_ashes"},
      {"text": "Leave it be.", "goto": "end"}
    ]}
 ]}
```

`use_block` shows its choice only when the player carries the item, then makes the player use that item on the speaking block exactly as a right-click would: the player's own matching stack is moved to the main hand, `ServerPlayerGameMode.useItemOn` runs (firing Forge's `RightClickBlock` and the block's own `use`), and the previous main-hand item is restored. The block decides what it consumes, as in normal play; `consume` does not apply to `use_block`.

**Triggers.** A dialogue with a `trigger` and no speaker opens as narration (no portrait):

```json
{"trigger": {"quest": "superiorstory:ignis_ashes", "stage": "collected"}, "beats": ["The ashes are still warm in your pack."]}
{"trigger": {"dialogue_end": "superiorstory:villager_prototype", "outcome": "good"}, "beats": ["..."]}
{"trigger": "command", "beats": ["..."]}
```

- `quest`: fires when that quest reaches `stage` (Section 3.3 lists stage keys; default `stage` is `collected`).
- `dialogue_end`: fires when the named dialogue ends; optional `outcome` (`good`/`bad`) and `reason` (default `completed`).
- `command`: opened with `/superiorstory open <dialogue id> [players]` (also usable from datapack functions).
- Triggered dialogues still honor `if`/`unless`. If the player is in a conversation or scene when a trigger fires, it waits and opens when they are free; at most one pending trigger per player (a newer one replaces it), dropped after 5 minutes.

**Structure pools and targeted search:**

```json
// data/<ns>/superiorstory/pools/boss_lairs.json   (pool ID derived from the path)
{"structures": ["cataclysm:*", "#bosses_rise:lairs"], "exclude": ["cataclysm:acropolis"]}

// another datapack, same path: adds to the pool above
{"structures": ["irons_spellbooks:*"]}

// another datapack, same path: throws away what lower packs defined
{"replace": true, "structures": ["cataclysm:burning_arena"]}
```

```json
{"text": "Let me think...", "locate": {"pool": ["superiorstory:boss_lairs", "superiorstory:cataclysm"]}}
{"text": "The arena? I know it.", "locate": {"structure": "cataclysm:burning_arena"}}
{"text": "Anything out there at all?", "locate": {"general": true}}
```

Pool files merge exactly like Minecraft tags. Every datapack's file for the same pool ID contributes, in datapack priority order: by default a file appends its `structures` (and `exclude`) to what lower-priority packs defined; `"replace": true` discards everything from lower-priority packs first. `exclude` is applied after the merge, like Forge's tag `remove`, so a higher pack can take a structure out of a bundled pool without replacing it. Every profile file also defines a pool named by its path (derived); that derived pool is the lowest layer, so a pool file with the same ID appends to it by default or replaces it with `"replace": true`. Because `SimpleJsonResourceReloadListener` keeps only the top file per ID, the pool loader reads all copies with `ResourceManager.listResourceStacks`, as Minecraft's `TagLoader` does. With no `pool`/`structure`/`general`, `locate` uses the derived pool of all `quest: true` profiled structures. The WIDE tier is removed. `general` is the only all-structure search: it reads the known-structure index (3.4) and fails rather than falling back to an unbounded search. Exactly one bundled dialogue, a dedicated villager conversation, uses `general`; every other bundled `locate` uses a pool or structure. Pool members are filtered to structures that can place in the player's dimension before any search. `only` is replaced by `structure` (atomic rename).

**Mini quest:**

```json
// data/<ns>/superiorstory/quests/ignis_ashes.json   (quest ID derived from the path)
{"structure": "cataclysm:burning_arena", "item": "cataclysm:burning_ashes",
 "reward": {"give": {"item": "minecraft:diamond", "quantity": 3}, "coins": 250}}
```

- `item` is an `ItemSpec`; its `quantity` (default 1) is how many the player must hand over, and each kill of the entity drops that many (derived).
- `structure` accepts a selector or pool. `entity` defaults to the structure profile's first boss when `structure` is one exact structure; when it is a pool, tag, or `ns:*`, `entity` is required (load error otherwise).
- `chance` (1.0) is optional.
- `repeatable` (false) lets a player take the quest again after turning it in; `cooldown` (optional, requires `repeatable`) is a duration (3.6) before it can be offered again, in real time. A non-repeatable quest a player has turned in, or one on cooldown, is never offered or shared to that player again; the `quest` condition exposes this as `{"quest": {"id": "ns:q", "stage": "available"}}` so dialogue can react.
- `if` / `unless` (optional) gate when the quest can be offered, using any registered condition, for example `"if": {"time": "night"}` for a quest only given at night.
- A blocked quest explains itself in dialogue (3.6).
- The item is added to the entity's loot through Minecraft's loot pipeline: a Story global loot modifier, `superiorstory:quest_drops`, bundled once in `data/forge/loot_modifiers/global_loot_modifiers.json`, reads the loaded quests and appends the item when a matching entity's death loot rolls. It always drops, for every player, with or without the quest. A loot modifier is used instead of `LootTableLoadEvent` because loot tables load before Story's quest data in the same reload. Dropped stacks are plain items; any matching item counts at turn-in.
- `reward` is an action map, so any action works there. `coins` is a number (default currency) or `{"amount": 250, "currency": "ns:id"}`. It is registered only when Superior Shop is loaded and pays through `grantCurrency` with source `superiorstory:<quest id>` and `GAIN_POPUP`. If the named currency is not registered (`grantCurrency` returns false), it pays the same amount in `internal:coins` and logs one warning, so the quest never breaks.

Dialogue verbs: `"locate": {"quest": "ns:q"}` finds its structure, `"accept": true` starts it, `hand_over` with `{"quest": ...}` turns it in. The giver reacts to items the player already carries:

```json
{"text": "Bring me the ashes from the Ignis.",
 "choices": [
   {"text": "I already have them.", "hand_over": {"quest": "superiorstory:ignis_ashes"}, "goto": "thanks"},
   {"text": "Where do I find it?", "unless": {"has_item": {"quest": "superiorstory:ignis_ashes"}}, "goto": "search"},
   {"text": "Not now.", "goto": "end"}
 ]}
```

### 3.3 Quest state

`StoryQuests` entries become quest instances with an optional quest definition ID. A located structure without a quest file is an implicit instance (reach, kill profile boss, turn in), so existing `quest_stage` content keeps its meaning.

Stage keys are the words authors write in `quest_stage` conditions and `quest` triggers. They are fixed so content, README, and code agree: `offered`, `accepted`, `visited`, `boss_defeated`, `collected`, `turned_in`. `collected` applies only to quests with an `item` and is satisfied by inventory at any time, so a player who already holds the item can turn in immediately. `quest_stage` also accepts `none` and `active` (unchanged); the per-quest `quest` condition additionally accepts `available` (can be offered now: never taken, or repeatable and off cooldown).

**Givers are roles, not entities.** A quest is never tied to a specific entity. Any speaker whose dialogue offers a turn-in for that quest (a `hand_over` with `{"quest": ...}`, or a `turn_in` action) can take it, anywhere. This supports the planned system that places the same kinds of NPCs in many structures and gives the illusion of one character who moves around. The instance stores only where it was accepted (position and dimension) and the speaker's display name. When the next stage is turn-in, the waypoint changes to "Return to <name>" at the acceptance position as a hint; turning in to any eligible speaker completes the quest and removes it.

**Kill credit is by contribution.** A boss death counts for its quest when it happens inside the structure bounding box plus 32 blocks, instead of the fixed 320 blocks. The box comes from the index record when there is one (B5); otherwise it is read from the live `StructureStart` in the structure's chunk, which is loaded because players are fighting there; if neither is available, a 160-block radius around the stored position is used. Credit goes to every player holding that quest who damaged the boss, directly or through an entity they own (resolved with Superior Lib `AllianceQueryApi.resolveOwner`, so summons, pets, and companions count). Story keeps an in-memory set of contributing player UUIDs per boss while it is alive, filled from `LivingHurtEvent`, and drops it on death or unload.

**Ally sharing (Superior Allies through Superior Lib).** Story calls only Superior Lib `AllianceQueryApi.areAllied`; when no provider is registered, quests are simply solo. The share radius is 16 blocks, same dimension. Sharing never splits or reduces rewards: every qualifying player gets their own full reward.
- **Accept**: when a player accepts a quest, each allied player within the share radius who could be offered it (not active, not finished-and-non-repeatable, not on cooldown) gets their own instance at `accepted`, their own waypoint, and an action-bar line naming who shared it.
- **Boss**: each player's own instance advances by the contribution rule above; being allied or nearby alone gives no kill credit.
- **Turn-in**: when a player turns in, each allied player within the share radius whose instance of the same quest is ready to turn in also completes it and receives the reward. For an item quest, "ready" means that player has kill credit for it or carries their own items; a player who qualifies by kill credit keeps their items. For a quest without an item, "ready" means `boss_defeated` when the structure has a profile boss, otherwise `visited`. Each player's repeat and cooldown state updates independently.

Every stage change posts `StoryQuestEvent` (renamed from `StructureQuestEvent`) and shows a short action-bar line (for example "Ignis defeated — return to Tobin").

### 3.4 Known-structure index (cross-addon; owner approved)

- **Contract** (Superior Lib API): `KnownStructureStartsApi` with `record(level, structureId, startChunk, boundingBox)` and `nearest(level, origin, idFilter, radius, excluded)`.
- **Store** (Superior Locator, approved 2026-09-29): per-dimension `SavedData`, bucketed in 512-block cells, one compact record per (structure, start chunk) with bounding box; O(1) append, ring query, no chunk loads.
- **Producers**: Worldgen V2 `NativeStartLedger` publishes each accepted start (worker-thread queue drained on server tick); real generation publishes from loaded chunks' structure starts, which covers dimensions without Superior Worldgen.
- **Consumers**: Story `general` and pool searches check the index first (nearest unsent member within radius, `min_size` from the stored box). A pool search falls back to the narrowed Locator search when the index has no member; `general` never falls back.

### 3.5 Presentation

- **Talk prompt** (`DialogueClient.onRenderGui`): height 14, padding 6, offset 18 below the crosshair, text `StoryChrome.LABEL` gray with the key a lighter gray, frame at reduced alpha. Also shown for talkable blocks.
- **No beam**: delete the beacon beam (`SuperiorSkylinesWaypoint.render` and its `AFTER_BLOCK_ENTITIES` hook in `SuperiorSkylinesClientEvents`). Waypoints are HUD markers only. No terrain-height work.
- **Marker anchor**: `addNamed` gains optional `y`. Story passes the structure's Y (from the Locator result or index box). Without a Y (user map waypoints), the marker keeps today's player-eye-height anchor.
- **Waypoint visibility**: idle marker alpha about 0.35 with no label; full opacity with label when the marker is within about 24 GUI px of the crosshair (fading out by about 80 px) or the player is within 48 blocks (fading by 128). Label background alpha reduced.
- **Marker style** (all waypoints; quest markers use gold `#F5B82E`, user markers keep their color): replace the filled diamond with a thin outlined crest, a small center pip, a slow soft halo pulse (about 2.4 s period), and a short scale-in on add. Procedural (no texture) unless screenshot review asks for art. `addNamed` gains an optional `kind` (`quest`/`user`).
- **Item hover everywhere**: item references render in the item's rarity color; hovering one calls `GuiGraphics.renderTooltip(font, stack, x, y)`, which Superior Tooltips already intercepts, so definition-bank content, stats, and CTRL details come with no new API. This covers item-valued variables (`{item}`, `{reward}`), authored inline references (`{item:cataclysm:burning_ashes}`, name derived from the registry; `{entity:ns:id}` works the same way for entities), and the item named on a `hand_over`/`use_block` choice (the choice row shows the item icon and quantity, hoverable). Item-valued variables use an `@item:` value prefix like the existing `Vars.ENTITY`; `Vars.PLACEHOLDER` accepts the `item:`/`entity:` forms.
- **Boss tier colors** (owned by Superior Lib `BossTierApi`; add the color there, not a Story-local table):

  | Tier | Color | Hex |
  | --- | --- | --- |
  | 1 | Gray | `#9A9A9A` |
  | 2 | Green | `#55C95A` |
  | 3 | Blue | `#4A8FE7` |
  | 4 | Indigo | `#6464E8` |
  | 5 | Purple | `#A855F7` |
  | 6 | Orange | `#F28C28` |
  | 7 | Gold | `#F5B82E` |
  | 8 | Yellow | `#F4E34C` |
  | 9 | Scarlet | `#F05245` |
  | 10 | Red | `#D92525` |

  `boss` spans, `{entity:...}` references to bosses, and boss speaker names use the tier color. The bundled `StructureFacts.danger` wording stays keyed to the same tiers.
- **Boss definitions with entity preview**: Superior Lib adds `TooltipSubject.Kind.ENTITY` and an entity-preview body element; Superior Tooltips renders it (cached client entity per type, slow rotation) and accepts `entity` subjects in definition JSON (guide updated in the same commit per Tooltips AGENTS). Default boss definitions are derived for every boss known to `BossTierApi` or Story profiles (tier-colored name, preview, tier, profile look/lore, lair names); authored definitions override. `DialogueScreen` queues the hover through `SuperiorTooltipClientPresentationApi.queueHover` when the mouse is over a boss span.

### 3.6 Durations and blocked quests

**Durations.** Every duration field (`cooldown` now, future timers later) uses one shared `Duration` primitive in real (wall-clock) time only: `"45s"`, `"30m"`, `"2h"`, `"1d"`, or combined units such as `"1h30m"`. It keeps running while the player is offline. There is no game-time or tick form (operator decision, for simplicity).

**Blocked quests explain themselves.** Whenever dialogue would offer or turn in a quest (a `locate` or `accept` for a quest, or a `hand_over` with `{"quest": ...}` whose items the player carries), Story checks whether the quest is available or active for the player; if not, it checks why and says so in the speaker's voice instead of silently hiding it:

| Reason | Default line (from `en_us.json`) |
| --- | --- |
| Cooldown | "I've nothing for you yet. Come back in {cooldown}." |
| Quest `if` fails on `time: night` | "Not while the sun's up. Come back after dark." |
| Quest `if` fails on `time: day` | "Not in this darkness. Come back when it's light." |
| Already done, not repeatable | "You've already done this for me. I won't forget it." |
| Already active | "You're already on that errand." |
| Any other failing condition | "Not now." |

Each condition module registers its own reason line (a lang key) alongside its compiler, so conditions added later explain themselves with no engine change; a module without one falls back to "Not now." Authors override per case in the dialogue: the line or choice may set `"blocked": "<branch>"`, and that branch receives `{blocked}` (the reason text) and `{cooldown}` (time left) as variables, so custom wording stays accurate. Without a `blocked` branch the default line plays and the conversation ends, because the following lines usually assume the quest went ahead. The time-of-day reason lines apply only in dimensions with a day cycle; in fixed-time dimensions a failing `time` gate uses "Not now."

`{cooldown}` is formatted as the two largest units left, such as "1d 4h", "2h 14m", or "45s". All unit words are lang keys.

**Time text color.** Time values (`{cooldown}` and any other duration or time-of-day variable) are drawn in their own color, `StoryChrome.NAME_TIME = #B9A8FF` (pale lavender), distinct from structure amber, biome green, and the boss tier colors. Adjusted only through screenshot review.

### 3.7 Data rules

- **Datapack overrides.** Pools merge like tags (3.2). Everything else (dialogues, quests, profiles) follows loot-table and recipe rules: a higher-priority datapack file at the same path replaces the lower one. Profiles still combine across different files from broad to specific, as today.
- **Loot check.** On reload, Story warns for each quest whose entity type has no death loot table, because the loot modifier cannot add to loot that never rolls (some bosses spawn chests or drop items in code). Bundled quests are checked in game against the installed jars.
- **Old save data.** Quest instances use a new player-data key; the old `superiorstory_quests` list is ignored and not converted (pre-release, no migrations).
- **Localization.** All player-facing text Story adds (action-bar lines, "Return to <name>", shared-quest notices, prompts, derived time/dimension words) lives in `assets/superiorstory/lang/en_us.json` so it can be translated; no player-facing literals in code.

## 4. Decisions

| ID | Decision | Result |
| --- | --- | --- |
| D1 | Owner of the known-structure store and its Lib contract | Superior Locator store, Superior Lib contract, Worldgen V2 + real generation as producers. Approved 2026-09-29 |
| D2 | Free `quest` for Story quests | Rename FTB keys to `ftb_quest` / `ftb_task` atomically |
| D3 | Marker style scope | All waypoints; quest markers gold |
| D4 | Quest drop gating | None: loot modifier, always drops; dialogue checks inventory. Operator decision 2026-09-29 |
| D5 | Waypoint beam | Removed; HUD marker only. Operator decision 2026-09-29 |
| D6 | Quest givers | Any speaker whose dialogue offers the turn-in; no entity IDs. Operator decision 2026-09-29 |
| D7 | Day/night source | `Level.isDay()` / `Level.isNight()` only. Operator decision 2026-09-29 |
| D8 | Coin currency | Default `internal:coins`; authored currency allowed; unknown currency falls back to default. Operator decision 2026-09-29 |
| D9 | Item input screen | Deferred; hand-over choices instead. Operator decision 2026-09-29 |
| D10 | Repeats | Once per player by default; `repeatable` and `cooldown` in JSON. Operator decision 2026-09-29 |
| D11 | Multiplayer | Per-player quests; proximity sharing with Superior Allies allies on accept and turn-in; kill credit by contribution; every qualifying player fully rewarded; no team-wide progress. Operator decision 2026-09-29 |
| D12 | Data rules, loot check, old data, localization, scenes deferred | As Sections 3.7 and 7. Operator approved defaults 2026-09-29 |
| D13 | Gating feedback and durations | Blocked quests state the actual reason (time of day, time left, done, active); durations in real time only (game time dropped); time text in its own color. Operator decision 2026-09-29 |

## 5. Repositories and checkouts

Run `git status` in each before editing; unrelated dirty files belong to other work.

| Addon | Checkout | Branch at planning time |
| --- | --- | --- |
| Story | `/home/alexh/superior-linux/addons/superior_story` | current branch |
| Skylines | `/home/alexh/superior-linux/addons/superior_skylines` (main checkout, not the sibling worktrees) | `recovery/high-throughput-native-api12-20260912`, the only branch containing `addNamed`/`removeAt` (`33759201`, `dca397a1`) |
| Worldgen V2 | `/home/alexh/superior-linux/addons/superior_worldgen_v2` (main checkout) | `integration/structure-caves-20260915` (contains `NativeStartLedger`) |
| Locator, Lib, Tooltips, Shop | their canonical checkouts | current branch |

If a checkout's branch differs from the table when implementation starts, stop and ask which line is live before editing it.

## 6. Batches

Each batch lands through the production path with its representative content. Validation follows root `AGENTS.md`: focused tests only for the contracts named below, one consolidated pass per batch, then in-game evidence.

### B1 Registry and context (Story); production path for everything else

- [x] `api/StoryHooks` with the five module kinds, compilers, `StoryContext`; delete `DialogueHooks`.
- [x] `IdSelector` replaces `StructureSelector`; `ItemSpec` primitive; `Dialogue` parses line `if`/`unless` and action keys on lines, actions from the registry with `JsonElement` values; `locate` becomes the first `LineKind`.
- [x] `DialogueServer` session uses a `Speaker` abstraction (entity speaker keeps NoAI hold, facing, restore key); conditions receive context; actions may contribute choice visibility.
- [x] Migrate `function`, `accept`, `turn_in`, `quest_stage`, FTB keys (D2); register `time`, `dimension`, `biome`, `has_item` conditions and `time`/`dimension` variables.
- [x] Bundled villager dialogue: day and night opening lines.
- Evidence: existing `DialogueTest`/`StructureQuestTest` updated and passing; one registry test that an unknown key fails only its file; in game, the villager greets differently by day and night.

### B2 Blocks, hand-over, triggers, Talk prompt (Story)

- [x] `block` speaker: client crosshair block probe, Talk packet variant for block positions, server range/line-of-sight checks, session ends if the block changes; block item portrait.
- [x] `hand_over` action (derived visibility, `quantity`, `consume`) and `use_block` action (Section 3.2).
- [x] Triggers `command` and `dialogue_end`; speakerless narration; pending-trigger rule.
- [x] Talk prompt restyle (3.5).
- [x] Bundled `altar_of_fire.json`.
- Evidence: in game, the altar option appears only with burning ashes in the inventory and spawns Ignis exactly as a manual right-click would; a `hand_over` choice is hidden without the items and removes them when picked (kept with `consume: false`); prompt screenshot.

### B3 Pools, dimension-filtered search, quest instances, boss kill visibility (Story; small Locator API)

- [x] Pool loader (`superiorstory/pools`) with tag-style layering (append by default, `replace`, `exclude` after merge, profile-derived pool as lowest layer) over all resource stacks, and the derived quest pool; `locate` `pool`/`structure`/`general`; remove the WIDE tier and `only`.
- [x] Dimension filtering of pool members; public Locator API for structure placement dimensions (reuse `collectStructureDimensions`).
- [x] Quest instances with the stage keys of 3.3 (implicit instances for located structures), `StoryQuestEvent`, acceptance position and giver name.
- [x] Kill detection by structure bounds + 32 with contribution credit (damage by the player or an owned entity), action-bar feedback, "Return to <name>" waypoint.
- [x] Bundled content: existing villager dialogues use pools; one dedicated villager conversation uses `general` (backed by B5; until B5 lands it fails gracefully).
- Evidence: focused test for pool layering (append, replace, exclude over a derived pool) and dimension filtering; in game, a Nether-only pool from the Overworld fails immediately with no Locator search; a pool search returns only members; killing the profile boss advances the stage with visible feedback.

### B4 Mini quests and rewards (Story; Shop compat)

- [x] Quest loader (`superiorstory/quests`), derived objectives, `quest` condition and trigger, entity-required-for-pool validation.
- [x] `superiorstory:quest_drops` loot modifier and its single bundled registration.
- [x] `hand_over` `{"quest": ...}` turn-in with automatic reward; `give` action; `coins` action with currency fallback (D8).
- [x] `repeatable` / `cooldown`, quest `if`/`unless`, the `available` stage, and the loot-table warning (3.7).
- [x] real-time `Duration` primitive; blocked-quest reasons with per-condition reason keys, `blocked` branch, `{blocked}` / `{cooldown}` variables; `NAME_TIME` color (3.6).
- [x] Ally sharing on accept and turn-in (3.3) through `AllianceQueryApi`; solo when no provider.
- [x] Bundled `ignis_ashes` quest and giver dialogue, including the "I already have them" option.
- Evidence: focused test for objective derivation, duration parsing and formatting, and cooldown/availability; in game, a night-only quest asked about by day gets the "come back after dark" line, and a repeatable quest on cooldown states the correct time left in the time color; in game with two allied players (Superior Allies), the nearby ally receives the quest on accept, both get credit only when both damaged Ignis, and both receive the full reward when one turns in nearby; in game, killing Ignis without the quest drops the ashes, a giver then shows "I already have them" and turns it in directly; the offer → kill → turn-in loop pays the item and changes the coin balance; an unknown currency pays `internal:coins`.

### B5 Known-structure index (Lib, Locator, Worldgen V2)

- [x] Lib contract; Locator `SavedData` store and query; Worldgen V2 ledger producer; real-generation producer.
- [x] Story `general` and pool index-first lookup.
- Evidence: in a fresh world after Surface LOD generation, the index holds starts in the LOD radius; the `general` villager resolves with zero Locator searches and zero chunk generation (debug counters); teleporting to the result lands inside the recorded bounds. Worldgen V2 producer cost is checked with its existing structure LOD diagnostics, not a new benchmark campaign.

### B6 Waypoints (Skylines; Story passes kind and Y)

- [x] Delete the beam. The render-stage hook stays as `updateProjections`: the HUD marker needs the world matrices, so it only projects and draws nothing.
- [x] `addNamed` optional `y` and `kind`; marker anchor; focus/distance opacity; label hiding; new marker style and animation.
- Evidence: the screenshot scene shows no beam and the marker sits at the structure; operator screenshot review of idle, focused, and near states.

### B7 Boss and item presentation (Lib, Tooltips, Story)

- [x] Item spans: `@item:` variables, inline `{item:ns:id}` / `{entity:ns:id}`, rarity color, hover via `renderTooltip` on text spans and hand-over/use-block choice rows.
- [x] Tier colors in `BossTierApi` (3.5 table); Story colors boss spans and boss speaker names.
- [x] `ENTITY` subject, entity-preview element, Tooltips renderer and definition support with guide update, derived boss definitions.
- [x] `DialogueScreen` boss-span hover queues the definition.
- Evidence: in a conversation naming Ignis, the name uses its tier color and hovering shows the definition with a rotating Ignis preview; an authored override replaces the derived text. Hovering burning ashes in text and in the hand-over choice shows the same full Superior item tooltip as the inventory.

### Final delivery

- [x] `README.md` documents every new key with the smallest example first.
- [x] No player-facing literals outside `en_us.json` (3.7).
- [x] Deploy the affected addon set with SHA-256 parity (Story; Skylines; Locator; Worldgen V2; Lib; Tooltips, as batches land). All six deployed with source/deployed parity (Section 9).
- [ ] (PENDING) Operator in-game acceptance of the fire altar, mini quest loop, day/night and dimension dialogue, waypoints, prompt, and boss and item hovers.

## 7. Deferred

- Item input screen (slot UI for submitting items). Hand-over choices cover v1; a future `input` line kind plugs into the same registry and `ItemSpec`.
- Waypoint beam. Reconsider only with a terrain-occlusion approach the operator approves.
- Scenes (first-join intro and other `StoryScene` files) keep their own format and do not use the module registry yet. Merging them is a later plan; do not attempt it here.
- Team-wide quest progress (FTB Teams or whole-alliance sharing). Sharing stays proximity-based (3.3).

## 8. Completion matrix

| # | Requirement (Section 1) | Batch | Implementation | Production integration | Validation | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | Modular + JSON-usable + derived contract in Story `AGENTS.md` | — | Done | n/a | n/a | Committed |
| 1 | Item hand-over from conversations, triggers, quests | B1, B2, B4 | Done (`hand_over` action with derived visibility, `quantity`, `consume`, quest form; icon rows) | Wired through the engine, bundled `villager_ashes.json` and `altar_of_fire.json` | Item spec and dialogue parsing tests pass; in-game hand-over not yet exercised | Story deployed |
| 2 | Mini quests: always-drop loot, inventory-aware dialogue, turn-in, item/coin reward | B4 | Done (quest loader, `quest_drops` global loot modifier, `give`/`coins`, `has_item`, turn-in) | Bundled `ignis_ashes` and giver dialogue; Ignis has a death loot table in the installed Cataclysm jar | Objective derivation and validation tests pass; the drop, turn-in and coin balance are not yet proven in game | Story deployed |
| 3 | Block speakers; `use_block` (fire altar → Ignis) | B2 | Done (`block` speaker, Talk packet block variant, server range/line-of-sight, `use_block` through `useItemOn`) | Bundled `altar_of_fire.json` | Parsing tests pass; the altar spawning Ignis is not yet proven in game | Story deployed |
| 4 | Dimension detection for dialogue and structure search | B1, B3 | Done (`dimension` condition and variable; pool members filtered by `LocatorStructureDimensions`) | `villager_nether.json`; `locate` fails at once when nothing can place | Parsing tests pass; in-game dimension checks pending | Story, Locator deployed |
| 5 | Day/night from Minecraft's state | B1 | Done (`time` condition and variable from `Level.isDay()`/`isNight()`) | Villager opening lines by day and night | Parsing tests pass; in-game greeting not yet seen | Story deployed |
| 6 | Waypoint opacity, quest marker, subtle animation | B6 | Done (attention fade, unlabeled idle marker, gold quest kind, outlined crest, pulse, scale-in) | Story passes the quest kind and Y | Skylines waypoint tests pass; operator screenshot review of idle, focused and near states pending | Skylines, Story deployed |
| 7 | Smaller gray Talk prompt | B2 | Done (height 14, padding 6, gray text, lighter key, reduced frame alpha; also for blocks) | Rendered by the Talk director | Compiles; screenshot pending | Story deployed |
| 8 | Beam removed; marker anchored at the structure | B6 | Done (beam deleted; `addNamed` takes `y`) | Story passes the structure Y | Skylines tests pass; screenshot of the beam scene pending | Skylines, Story deployed |
| 9 | Named-boss kill detection near the quest structure, with feedback | B3 | Done (structure bounds + 32 with fallbacks, contribution credit, action-bar lines, "Return to <name>" marker) | Runs on `LivingHurtEvent` and `LivingDeathEvent` for profile and quest bosses | Not covered by a unit test (needs a live world); in-game kill pending | Story deployed |
| 10 | Boss tier colors and definition hover with entity preview | B7 | Done (tier colors in `BossTierApi`; `ENTITY` subject and surface; entity element and renderer; boss facts synced from profiles; derived boss definition; guide updated) | Dialogue boss spans and speaker names use the tier color and queue the hover | Tooltips definition contract test passes; the rotating preview and the authored override are not yet seen in game | Lib, Tooltips, Story deployed |
| 11 | General location in one villager only; recorded structure starts | B3, B5 | Done (WIDE removed; `general` reads the index only; `villager_cartographer.json` is the one bundled user; Locator store; real-generation and Worldgen sink producers) | Story queries the index first | Index store tests pass (dedupe, ring query, filters, save/load); zero-search and zero-generation counters and the LOD-radius fill are not yet measured in game | Lib, Locator, Story, Worldgen V2 deployed |
| 12 | Structure pools; targeted pool/structure search | B3 | Done (tag-style layering over all resource stacks with derived profile pool, `pool`/`structure`/`general`/`quest`) | The derived profile pool (a bare `locate`) replaced the bundled `boss_lairs.json`; `safe_outposts.json` uses the village tag | Pool layering test passes (append, replace, exclude over the derived pool) | Story deployed |
| 13 | Item hover with full tooltip wherever an item appears in conversation | B7 | Done (`@item:` variables, inline `{item:...}`, rarity color, hover on text and hand-over rows through `renderTooltip`) | Text spans and choice rows in `DialogueScreen` | Placeholder tests pass; the full Superior tooltip on hover is not yet seen in game | Story deployed |
| 14 | Once per player by default; optional `repeatable` and `cooldown` | B4 | Done | Availability checked on locate, accept and turn-in | Quest rule and duration tests pass; cooldown and availability need a live player, not unit tested | Story deployed |
| 15 | Proximity ally sharing on accept/turn-in; contribution kill credit; full reward each | B3, B4 | Done (through `AllianceQueryApi`; solo without a provider) | Accept shares, turn-in completes ready allies, each rewarded | Needs two allied players in game | Story deployed |
| 16 | Blocked quests state the real reason and time left; real-time durations; colored time text | B4 | Done (`StoryBlocked`, per-condition reason keys, `blocked` branch, `{blocked}`/`{cooldown}`, `Duration`, `NAME_TIME`) | Quest availability and `hand_over` raise it; the engine plays the branch or the default line | Duration parse and format tests pass; the in-game lines and lavender color pending | Story deployed |

## 9. Evidence and open items (September 29)

- **Proven:** Story compiles and its 31 unit tests pass (registry, conditions, triggers, pools, quests, durations, item specs, placeholders, bundled content); Skylines waypoint tests, Locator index tests and the Tooltips definition contract test pass. All six addons are deployed with source and deployed SHA-256 parity: Story d8d874f1 (commit 77e4c1f), Lib a81d20f3 (5cd2ab43), Tooltips 5335fcc0 (b5d07b5), Locator 97fdad4a (d17a16f), Skylines e1776c74 (18977ea1), Worldgen V2 317987cb (add9d5dfb, built in a clean detached worktree on top of the deployed f8d00e796 so peers' uncommitted decoration work was not shipped; its entry list differs from the previous jar only by the version-named injection folders and the sink classes).
- **Not proven (no in-game run was possible from the implementing session):** every "in game" evidence line in Section 6. The engine, packets, loot modifier, waypoint and hover paths are wired end to end but unexercised. The operator acceptance row of Final delivery stays open.
- **Deviation:** the beam's render-stage hook remains as `updateProjections` (Section 6, B6).
- **Worldgen V2:** the sink and ledger publish are committed on `integration/structure-caves-20260915` and deployed. The Locator reaches `NativeStartSink` by class name; the deployed jar contains it unrelocated. Whether Surface LOD starts arrive in the index is not yet measured. The index also fills from real chunk loading in every dimension.
- **Runtime notes for acceptance:** the client needs the deployed Story, Lib, Tooltips and Skylines together (`addNamed` gained `y` and `kind`; the Story network version is 6). Quests need Superior Shop installed for the bundled `coins` reward; without it `ignis_ashes.json` is skipped with an error naming `coins`.