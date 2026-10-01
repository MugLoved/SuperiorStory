# SuperiorStory

First-join intro and datapack-authored scenes for Superior (Forge 1.20.1 / 47.4.20).

- Text lives in `src/main/resources/assets/superiorstory/lang/en_us.json` (can also be overridden by a resource pack, no rebuild needed).
- Sound assets: `assets/superiorstory/sounds/*.ogg`; Superior Sounds owns their selection, music lane, and playback through `assets/superiorstory/superior_sounds/audio/intro.json`.
- Superior Respawn owns invulnerability while a scene is active.
- Compare the originals in game (op): `/superiorstory play superiorstory:hardcoded_intro`, then `/superiorstory play superiorstory:intro`. Each command starts that scene immediately and can be repeated; manual playback does not change the first-join flag. An optional `[players]` target is accepted.
- Existing commands remain: `/superiorstory replay [players]` resets and replays the hardcoded intro; `/superiorstory reset [players]` makes the first-join scene play on the next join.
- Detects Superior Skill Tree or Puffish Skills at runtime and opens the installed tree from the same final beat. Superior Skill Tree takes priority if both are installed.
- Build from this canonical Linux checkout with Gradle 8.14.5.

## Author a scene

Add one JSON file to a datapack at `data/<namespace>/superiorstory/scenes/<name>.json`. Its command ID is `<namespace>:<name>`. The bundled `data/superiorstory/superiorstory/scenes/intro.json` is a complete five-beat example and a manual copy of the hardcoded intro. Run `/reload` after editing a datapack, then `/superiorstory play <id>`.

The smallest scene is `{"beats":["One line.","Another line."]}`. Each string is one typed beat with the default pace and a continue prompt. A beat object may set `text`, `pace` (0.1–5; larger is slower), `accent` (boolean), and exactly two `choices` (both advance). Text, full beats, and choice labels may use `{"translate":"translation.key"}`. The bundled replica uses the hardcoded intro's translation keys, so both scenes share the same text in every language. Up to 32 beats are accepted. `end` defaults to `"reveal"`; set `"end":"skill_tree"` to use the installed tree handoff on the final beat. Scenes are manual by default. Set `"trigger":"first_join"` on one scene to select it for first joins; without one, the hardcoded intro remains the first-join scene. The first-join flag is per player per world. Invalid scenes are logged and skipped on reload.

Story publishes `superiorstory:scene_active` and `superiorstory:scene_id` to Superior Sounds. Its audio catalog owns music and UI cues; add or override Sounds catalog rules for scene-specific audio.


## NPC dialogue

Press the Talk key (default R, rebindable) while looking at an NPC or a block that has a dialogue. A small gray `[R] Talk` prompt appears under the crosshair. An NPC freezes (vanilla NoAI, restored afterwards) and turns to face you, Superior HUD fades out, and a box opens at the bottom of the screen: a still portrait of the speaker on the left (an entity, or a block's item; none for narration), the typed line on the right, choices below. Esc leaves. Walking about 8 blocks away, taking damage, or the speaker dying or changing also ends it.

Over a mob you can talk to, the server draws a marker for you alone: a **?** (within about 6 blocks) when it has a dialogue for you, and a **!** (from about 24 blocks) when talking would move one of your quests forward: a turn-in or quest hand-over that is ready (you carry the items, or finished the job), or a delivery objective waiting on that NPC. The **!** replaces the **?**, and an NPC that only offers a new quest shows the **?**. Nothing is authored: a dialogue's `turn_in`, `deliver`, and quest `hand_over` actions say whether they would progress (an action module can override `StoryHooks.Action.progresses`). Markers are hidden while a dialogue is open and with the HUD hidden (F1). The icons are Superior Lib textures (`icons/icon_exclamation.png`, and `icons/question_mark.png` until a matching "?" is drawn).

A dialogue is a scene file that names who talks, in the same folder as scenes: `data/<namespace>/superiorstory/scenes/<name>.json`. The smallest one:

```json
{ "npc": "borin", "beats": ["Cold out, ain't it?", "Mind the peaks."] }
```

- `npc` picks a mob with vanilla selectors: a plain word is an entity tag (`/tag @e[type=villager,limit=1,sort=nearest] add borin`), `minecraft:villager` is an entity type, `#ns:tag` is an entity type tag. The name defaults to the entity's display name and the portrait to the entity itself. `block` (below) binds a block instead, and `trigger` (below) opens the dialogue by an event as narration.
- `beats` is the main branch. Each entry is a string, `{"translate":"key"}`, an array of variants (one is picked at random), or `{"text": ..., "accent": bool, "pace": 0.1-5, "choices": [...]}`.
- A choice is a string (continues to the next line) or an object: `text` (required), `goto` (`"end"` or a branch name; default is the next line, and the conversation ends after the last line), `outcome` (`"good"` or `"bad"`), `id`, `if`/`unless`, `blocked`, and action keys. 1-4 choices per line; number keys pick them.
- `branches` maps branch names to line arrays that `goto` can jump to. `speaker` overrides the name. `priority`, `if`, and `unless` choose between several dialogues bound to the same speaker (higher priority wins; a conditional dialogue beats an unconditional one).
- Every unset field takes its default. An invalid file is skipped on reload with the file and field named while its siblings stay loaded; branches nothing can reach are warned about.

Bundled examples: `example_dialogue.json` (npc tag `story_example`; try it with `/summon minecraft:villager ~ ~ ~ {Tags:["story_example"]}` after `/reload`), the villager set `villager_prototype/return/after/nether.json` (tag `story_villager`), `villager_cartographer.json` (tag `story_cartographer`, the one bundled conversation that searches every structure), `villager_ashes.json` (tag `story_alchemist`, the mini quest giver), and `altar_of_fire.json` (a block speaker).

### The module registry

Everything a dialogue can do is a module registered under a JSON key in one registry, `com.mugloved.superiorstory.api.StoryHooks`, with a compiler that runs once when datapacks load. A module plugs in wherever its kind is accepted; adding one edits nothing else. Register during mod construction so typos are rejected at load. Other mods extend Story only this way.

| Kind | Where it is written | Built in |
| --- | --- | --- |
| Condition | `if` / `unless` on a dialogue, line, choice, quest, or trigger | `quest_stage`, `quest`, `time`, `dimension`, `biome`, `has_item`, `profession`, `unlocked`, `ftb_quest` |
| Action | a key on a choice, a line (runs when the player continues past it), or a quest `reward` | `function`, `accept`, `turn_in`, `hand_over`, `use_block`, `give`, `unlock`, `lock`, `coins`, `open`, `ftb_quest`, `ftb_task` |
| Location | a quest or `also` objective `location` | `structure`, `pool`, `biome` |
| Item source | a quest `item` object | `fish` (with Starcatcher); a plain item spec needs no module |
| Line kind | a key on a line | `locate` |
| Speaker | a dialogue root key | `npc`, `block` |
| Trigger | the dialogue `trigger` | `command`, `dialogue_end`, `quest` |
| Variable source | derived variables for every line | `player`, `time`, `dimension`, `speaker` |
| Text source | an alternative to `text` on any line | `pool` |
| Text transform | authored text before it is sent | `{~word}` |

### Dialogue and word pools

Each talk rolls again, including a line revisited by `goto`. Put interchangeable lines in `data/<ns>/superiorstory/line_pools/<name>.json` and words in `word_pools/<name>.json`. File paths supply IDs; an unqualified reference uses the dialogue's namespace (inside a pool, that pool's namespace).

```json
{"lines":["{~hail}, {~friend}.","Good to see you, {~friend}.",{"text":"Back again, {player}!","weight":2,"if":{"rep":{"min":8}}}]}
```

```json
{"words":["friend","traveler",{"text":"stranger","unless":{"rep":{"min":0}}}]}
```

```json
{"npc":"story_villager","beats":[{"pool":"greeting"},{"pool":"smalltalk"},"A fixed line.",{"pool":"farewell"}]}
```

`pool` replaces `text`; every other line field still works, including choices, actions, line kinds, `pace`, `accent`, `needs`, and guards. Entries accept a string, `{"translate":"key"}`, or `{"text":...,"weight":1,"if":...,"unless":...}`. Ordinary conditions run at pick time. Random selection uses positive integer weights and excludes the previous entry when at least two entries are eligible. Last picks are kept per player and pool in memory and cleared on logout or reload. A line pool with no eligible entry skips its line; a word pool with none contributes an empty phrase (a wholly empty text uses the localized ellipsis). Resolved words travel with the original text, so translated entries still use the client's language. Translations must preserve the same word tokens and occurrence counts as their default-language text.

Line pools default to `"mode":"random"`. `"mode":"first"` picks the first eligible entry in file order, useful for a tiered greeting. Both modes exclude the previous entry when alternatives are eligible, so `first` chooses the first remaining entry. Word pools are random. `{~name}` and `{~ns:name}` expand independently in line and choice text before ordinary `{variable}` filling. Word entries may nest other word pools to depth 4. Unknown references, cycles, deeper nesting, malformed fields, empty pools, and text that can expand beyond the 512-character text limit are load errors; valid siblings remain active. Each pool allows up to 512 merged entries.

Datapacks with the same pool ID append entries in pack order. `"replace":true` discards lower layers. A higher line-pool file supplies the effective mode (default random). Add an entry to enrich every dialogue using that pool. Bundled villager, cartographer, alchemist, and generic bounty openers demonstrate reuse.

Other mods register `TextSource` compilers with `StoryHooks.registerTextSource`, and expansions with `registerTextTransform`; their validation runs with the pool catalogue installed, before a dialogue becomes active.

Shared forms: an item is `"ns:item"`, `"#ns:tag"`, or `{"item": ..., "quantity": 1, "consume": true}` (several: an array, all required). A selector is an exact ID, `ns:*`, or `#ns:tag`. A duration is real (wall-clock) time only: `"45s"`, `"30m"`, `"2h"`, `"1d"`, `"1h30m"`; it keeps running while the player is offline.

### Conditions

Conditions work at dialogue, line, and choice level; a failing line is skipped like `needs`, a failing choice is hidden.

```json
{"text": "Nobody walks out here this late.", "if": {"time": "night"}}
{"text": "Bright day for a stranger to wander in.", "if": {"time": "day"}}
{"npc": "story_villager", "if": {"dimension": "minecraft:the_nether"}, "beats": ["You should not be here."]}
```

- `time`: `day` or `night`, read from Minecraft's own state (`Level.isDay()` / `isNight()`), so mods that change the day cycle are respected. In fixed-time dimensions (the Nether, the End) neither value matches; use `dimension` there.
- `dimension`: an ID, `ns:*`, or an array. `biome`: the same with tags, at the player's position.
- `has_item`: an item or array of items the player carries, or `{"quest": "ns:q"}` for that quest's item.
- `profession`: `{"if": {"profession": "fisherman"}}` (an ID, default namespace `minecraft`, or an array) is true when the speaking entity is a villager with that profession.
- `unlocked`: true when the player holds every listed unlock key (see "Unlock keys" below).
- `quest_stage`: the newest quest: `none`, `active`, `offered`, `accepted`, `visited`, `boss_defeated`, `collected`, `turned_in`. `quest`: one quest, `"ns:q"` (in progress) or `{"id": "ns:q", "stage": "..."}`, which also accepts `available` (can be offered now).
- With FTB Quests installed: `ftb_quest` (`"if": {"ftb_quest": "<id>"}` tests completion).

### Choices that take items

A choice with `hand_over` is shown only when the player carries the items, and picking it takes them (unless `consume` is false) before its other actions run. The row shows the item icon and count and is hoverable.

```json
{"text": "Here, the ashes you asked for.", "hand_over": "cataclysm:burning_ashes"}
{"text": "Three blaze rods, as promised.", "hand_over": {"item": "minecraft:blaze_rod", "quantity": 3}}
{"text": "Show her the key.", "hand_over": {"item": "minecraft:trial_key", "consume": false}}
{"text": "I have what you need.", "hand_over": {"quest": "superiorstory:ignis_ashes"}}
```

The `{"quest": ...}` form takes the item from the quest file, turns the quest in, and pays its reward, whether or not the player accepted it first. `give` gives items (`"give": {"item": "minecraft:diamond", "quantity": 3}`); `coins` pays Superior Shop coins when that mod is installed (below). `open` opens another dialogue that has a `trigger`.

### Talking to blocks

```json
{"block": "cataclysm:altar_of_fire",
 "beats": [
   "The altar is cold. Old soot fills its bowl.",
   {"text": "It hungers for something that still burns.",
    "choices": [
      {"text": "Place the burning ashes.", "use_block": "cataclysm:burning_ashes"},
      {"text": "Leave it be.", "goto": "end"}]}]}
```

`block` takes an ID, `ns:*`, or `#tag`. `use_block` shows its choice only when the player carries the item, then makes the player use it on the speaking block exactly as a right-click would (the stack moves to the main hand, `useItemOn` runs and Forge's right-click events fire, the previous main-hand item returns). The block decides what it consumes. The conversation ends if the block changes.

### Triggers

A dialogue with a `trigger` and no speaker opens as narration (no portrait):

```json
{"trigger": {"quest": "superiorstory:ignis_ashes", "stage": "collected"}, "beats": ["The ashes are still warm in your pack."]}
{"trigger": {"dialogue_end": "superiorstory:villager_prototype", "outcome": "good"}, "beats": ["..."]}
{"trigger": "command", "beats": ["..."]}
```

`quest` fires when that quest reaches a stage (default `collected`). `dialogue_end` fires when the named dialogue ends, with optional `outcome` and `reason` (default `completed`). `command` opens with `/superiorstory open <dialogue> [players]` (also from datapack functions). Triggered dialogues honor `if`/`unless`. If the player is in a conversation when one fires it waits and opens when they are free; at most one is pending per player (a newer one replaces it) and it is dropped after 5 minutes.

### Events and hooks for other mods

Posted on `MinecraftForge.EVENT_BUS`, server side, from `com.mugloved.superiorstory.api`:

- `DialogueChoiceEvent(player, npc?, dialogueId, choiceId, outcome, endsDialogue)` for every choice picked (`npc` is null for a block or narration).
- `DialogueEndEvent(player, npc?, dialogueId, reason, lastOutcome)` once when it closes; `reason` is `COMPLETED`, `LEFT`, `INTERRUPTED`, or `NPC_LOST`.
- `StoryQuestEvent(player, questId?, locationKind, locationId, position, stage)` on every quest stage change (`locationKind` is `structure`, `pool`, `biome`, or another location module's key, empty for a fetch quest).

Listeners only subscribe. To add JSON keys, register a module with `StoryHooks.registerCondition/registerAction/registerLineKind/registerSpeaker/registerTrigger/registerVarSource/registerLocation/registerItemSource`; `StoryHooks.fire(key, player, payload)` fires a trigger. Built in: `"function": "ns:path"` runs a datapack function as the player (permission level 2). With FTB Quests installed, `"ftb_quest": "<id>"` / `"ftb_task": "<id>"` complete that quest or task for the player's team (Copy ID in the quest editor).

Story publishes `superiorstory:dialogue_active` to Superior Sounds alongside the scene signals.

## Structure quests

A dialogue line can send the player to a structure they have not been sent to yet, from any mod, with nothing hard-coded.

```json
{"text": "Let me think...", "locate": {"found": "found", "failed": "nothing"}}
{"text": "The arena? I know it.", "locate": {"structure": "cataclysm:burning_arena"}}
{"text": "Anything out there at all?", "locate": {"general": true}}
{"text": "Let me think...", "locate": {"quest": "superiorstory:ignis_ashes"}}
```

The target is at most one of `pool` (an ID or array), `structure` (an ID, `ns:*`, or `#tag`), `general`, or `quest`; with none, the derived pool of every profiled structure a quest may target. Members are filtered to structures that can generate in the player's dimension before anything is asked, so a Nether-only pool from the Overworld fails immediately. The known-structure index (below) is asked first; pool and structure searches fall back to a Superior Locator search of only those members, and `general` never does. `radius` (default 4000), `min_size` (default 24, skips decorations by bounding box in the index) and `waypoint: false` (no map marker) are optional. Continue stays busy (pulsing dots) until the search settles, then goes to `found` (default: next line) or `failed` (default: end). A structure is never offered twice to a player; `/superiorstory forget [players]` clears that. (`only` was renamed to `structure`.)

On success the line's `found` branch continues with these variables for the rest of the conversation. Use `{name}` or `{name|fallback}` anywhere in text or choice labels:

- always: `structure` (cleaned name, amber), `id`, `distance`, `direction`, `biome` (green), and derived `time` (`day`/`night`), `dimension`, `speaker`
- from a profile: `look`, `lore`, `keywords`, `boss` (localized name in its tier color, hoverable), `danger` (from the Superior Lib boss tier)
- from a quest: `item`, `reward` (item names in their rarity color, hoverable), `boss`
- inline references: `{item:cataclysm:burning_ashes}` and `{entity:cataclysm:ignis}` name an item or entity anywhere; the name comes from the registry.
- in a blocked branch: `{blocked}` (the reason) and `{cooldown}` (time left, in its own lavender color)

A line with `"needs": "look"` (or a list) is skipped when that variable is unset, so generic dialogue degrades gracefully for unprofiled structures.

Every item shown in a conversation (text, variables, hand-over rows) is drawn in its rarity color and shows Superior Tooltips' full item tooltip, definition-bank content included, when hovered. A boss name shows its tier color (gray, green, blue, indigo, purple, orange, gold, yellow, scarlet, red for tiers 1-10, owned by Superior Lib) and hovering it shows a definition with a slowly turning live preview, its tier, look, lore, and lair names. An authored Tooltips `entity` definition can replace that text (see Superior Tooltips' JSON guide).

### Profiles: what you know about a structure

`data/<ns>/superiorstory/structures/<name>.json`, one object or an array. Every field but `structures` is optional:

```json
{"structures": ["cataclysm:burning_arena"], "name": "The Burning Arena",
 "look": "a blackstone coliseum ringed with lava", "keywords": ["fire", "lava"],
 "boss": ["cataclysm:ignis"], "lore": "Something terrible was sealed here.", "quest": true}
```

`structures` takes IDs, `ns:*` for a whole mod, or `#tag`. Profiles merge from broad to specific (the mod-wide one supplies defaults, the exact one overrides; keywords add up). The first `boss` is the one named in dialogue; every listed boss counts toward finishing the quest. `quest: false` stops a structure from ever being offered. Every profile file also defines a pool named by its path (`structures/cataclysm.json` is the pool `superiorstory:cataclysm`). Bundled profiles cover Cataclysm (including Integrated Cataclysm's shrine), Iron's Spells 'n Spellbooks (and Wind's windmill), Bosses'Rise, Legendary Monsters, and Call of Yucatan.

### Structure pools

`data/<ns>/superiorstory/pools/<name>.json`; the path is the pool ID:

```json
{"structures": ["cataclysm:*", "#bosses_rise:lairs"], "exclude": ["cataclysm:acropolis"]}
```

Pool files merge exactly like Minecraft tags: every datapack's file for the same ID contributes in pack priority order. By default a file appends its `structures` (and `exclude`) to what lower packs defined; `"replace": true` discards everything from lower packs first; `exclude` applies after the merge. A profile file's derived pool is the lowest layer, so a pool file with the same ID appends to it or replaces it. Bundled: `safe_outposts.json` (the village tag). A `locate` with no target searches the derived pool of every profiled structure a quest may target, so there is no hand-kept list of boss lairs.

### Dedicated dialogue for a structure

A dialogue file with `"structure": "ns:id"` (or `ns:*`, or `#tag`) instead of a speaker continues the conversation when a located structure matches; the exact ID beats a tag, which beats a whole mod. It uses the same lines, branches, choices, and variables. If none matches, the line's `found` branch plays. Examples: `structure_cataclysm.json`, `structure_sunken_city.json`.

### Mini quests

`data/<ns>/superiorstory/quests/<name>.json`; the path is the quest ID. Location, entity, item:

```json
{"location": {"structure": "cataclysm:burning_arena"}, "item": "cataclysm:burning_ashes",
 "reward": {"give": {"item": "minecraft:diamond", "quantity": 3}, "coins": 250}}
{"location": {"biome": "#minecraft:is_ocean"}, "item": "minecraft:cod"}
{"location": {"pool": "superiorstory:safe_outposts"}}
{"item": "minecraft:cod"}
```

- `location` is optional and names one location kind (a module; other mods register more with `StoryHooks.registerLocation`): `structure` (an ID, `ns:*`, `#tag`, or an array), `pool` (a pool ID or array), `biome` (an ID, `#tag`, or an array, searched with Superior Locator's biome search; the player's own biome counts at once). A quest with no location is a fetch quest: no search, no marker, no visit stage; `collected` and the turn-in work as usual. The old quest-level `structure` field is gone (a load error names the replacement).
- `item` is required to hand over anything; it is an item spec whose `quantity` (default 1) is how many the player hands over, and each kill of the entity drops that many. It must be one exact item ID, or an item source (below).
- `entity` defaults to the structure profile's first boss when the location is one exact structure; for a structure selector or pool with an item it is required (load error otherwise). `chance` (default 1) is the drop chance. A biome location credits a kill inside a matching biome; a structure or pool keeps the bounding box plus 32 blocks.
- `mods` (an array of mod IDs) skips the file silently when a named mod is absent, as on a dialogue.
- Item sources: `"item": {"<source>": value}` names a module (`StoryHooks.registerItemSource`) that picks the items when the quest is offered and stores them in the player's quest instance; every reader of the quest's items (`hand_over {"quest"}`, `has_item {"quest"}`, `collected`, the facts) reads those picks. Built in with Starcatcher: `fish` (below).
- The item is added to the entity's loot through Minecraft's loot pipeline (the global loot modifier `superiorstory:quest_drops`), so it always drops, for every player, with or without the quest: a rare boss killed early never blocks progression. On reload Story warns for a quest whose entity has no death loot table.
- `reward` is an action map; any action works. `coins` needs Superior Shop: a number, or `{"amount": 250, "currency": "ns:id"}`; an unknown currency pays the same amount in `internal:coins` and logs one warning.
- Quests are done once per player. `repeatable: true` lets a player take it again after turning it in; `cooldown` (needs `repeatable`) waits that long in real time first. `if`/`unless` gate when it can be offered, for example `"if": {"time": "night"}`.
- A blocked quest explains itself in the speaker's voice instead of hiding: cooldown ("Come back in {cooldown}"), night-only or day-only ("Come back after dark" / "when it's light"), already done, already active, or "Not now". A line or choice may set `"blocked": "<branch>"` to author its own wording; that branch gets `{blocked}` and `{cooldown}`. Without one the default line plays and the conversation ends. Each condition module supplies its own reason lang key.

Dialogue verbs: `"locate": {"quest": "ns:q"}` finds its structure, `"accept": "ns:q"` (or `true` for the newest offer) starts it, `hand_over` with `{"quest": ...}` turns it in. The giver reacts to items the player already carries:

```json
{"text": "Bring me the ashes from the Ignis.",
 "choices": [
   {"text": "I already have them.", "hand_over": {"quest": "superiorstory:ignis_ashes"}, "goto": "thanks"},
   {"text": "Where do I find it?", "unless": {"has_item": {"quest": "superiorstory:ignis_ashes"}}, "goto": "search"},
   {"text": "Not now.", "goto": "end"}]}
```

Bundled: `ignis_ashes.json` with the giver `villager_ashes.json`.

### Quest progress

Each offered structure or quest is tracked per player. Stage keys: `offered`, `accepted`, `visited`, `boss_defeated`, `collected` (the player carries the quest item, judged from the inventory at any time), `turned_in`. Reaching the site (within 48 blocks) advances it; a boss death counts when it happens inside the structure's bounding box plus 32 blocks (from the known-structure index or the live structure start; 160 blocks around the recorded position when neither is known) and the player helped: credit goes to every player holding the quest who damaged the boss, directly or through a summon, pet, or companion they own (Superior Lib `AllianceQueryApi.resolveOwner`). Every stage change posts `StoryQuestEvent` and shows a short action-bar line.

A quest is never tied to a giver: any speaker whose dialogue offers the turn-in (`hand_over` with a quest, or `turn_in`) can take it, anywhere. Accepting places the marker; when the next step is turning in, the marker becomes "Return to <name>" at the place it was accepted.

Allies (Superior Allies through Superior Lib; solo when none is installed) within 16 blocks share it: allies who could be offered the quest get their own instance when you accept; when you turn in, each nearby ally whose own quest is ready (kill credit or carrying the item; for a quest with no item, the boss defeated or the site visited) completes it too. Nothing is split: every qualifying player gets a full reward.

Conditions read the newest quest (`"if": {"quest_stage": "boss_defeated"}`); `villager_prototype.json`, `villager_return.json`, and `villager_after.json` show the whole loop with one villager.

### Extra objectives: several bosses and deliveries

`also` lists further objectives beside the main location; each takes the same `location` object. Each gets its own structure (found through the known-structure index, then Superior Locator, never the same one twice) and its own map marker; the quest cannot be turned in until every one is done, and the "Return to <giver>" marker appears when the last one finishes. Allies share the structures already found, not the progress.

```json
{"location": {"structure": "cataclysm:cursed_pyramid"}, "entity": "cataclysm:ancient_remnant",
 "also": [
   {"location": {"structure": "cataclysm:frosted_prison"}, "entity": "cataclysm:maledictus"},
   {"location": {"pool": "superiorstory:safe_outposts"},
    "deliver": {"type": "minecraft:villager", "tag": "seal_courier", "name": "Sealwright Orin"}}]}
```

- A boss objective (`location` + `entity`; the entity defaults to the profile's first boss for one exact structure) is done when the boss dies inside that structure and the player helped.
- A `deliver` objective names a structure the author chose to be safe (a pool of villages is typical). When the player is detected inside it, an NPC of `type` (default `minecraft:villager`, must be a mob) is spawned next to them: invulnerable, frozen, glowing gold through walls, tagged `tag`, and removed again after delivery, when the player walks 96 blocks away (it spawns again on the next visit), or after a crash. The marker moves from the structure to the NPC. A scene binds to it with `"npc": "<tag>"`, and its choice carries the action `"deliver": "ns:quest"` (or `true` for any) to finish the objective. Guard the scene with `"if": {"quest": {"id": "ns:quest", "stage": "active"}}`.
- Chains need no extra field: gate a quest with `"if": {"quest": {"id": "ns:first", "stage": "turned_in"}}`. Rotating bounties are `repeatable` + `cooldown` + a pool `location`.

Bundled quests:

- `ignis_ashes`: bring `burning_ashes` from Ignis (Maren); the fire altar summons it.
- `twin_seals`: two bosses and a delivery, chained after `ignis_ashes` (Maren, Orin); `three_deaths`: three bosses in three structures, chained after `twin_seals` (Maren).
- `sunken_proof`: repeatable, kill the Leviathan (Wren); `abyssal_offering`: bring back its `tidal_claws`, or call it to the Altar of Abyss with the crafted `abyssal_sacrifice` (Wren, `altar_of_abyss.json`).
- `courier_letter`: a visit-only errand with a delivery NPC, no combat (Tobin gives the letter, Innkeeper Aldric takes it).
- `shrine_for_tobin` and `shrine_for_wren`: the same shrine and boss for two rivals. Each is blocked once the other is turned in, pays differently (bread against coin), and the other NPC remarks on your choice.

A quest with no `item` and no `entity` on a pool or selector target is a visit: it completes when the player reaches the structure. Only a random offer (`locate` with a pool or no target) is limited to structures profiled for quests; a `locate` that names a structure or a quest searches exactly what the author chose.

### Facts a scene gets without being told

A scene that accepts, hands over, turns in, delivers, or locates a quest (`accept`, `hand_over`, `turn_in`, `deliver`, or `locate` with a quest) is filled with that quest's facts from its first line: `{structure}` (the quest's structure, when it names one exact structure), `{boss}` (its entity, else the structure profile's first boss), `{danger}` (that boss's Superior Lib tier in words), `{item}` and `{reward}`. The quest the player is on wins when a scene names several. Write `Bring me {item} from {boss}; it waits in the {structure}, {danger}.` and no IDs. For a creature or place outside the scene's quest, `{entity:ns:id}`, `{structure:ns:id}` and `{danger:ns:entity}` name it directly.

`boss_tier` condition: `{"boss_tier": {"min": 7}}` (and/or `max`, 1 to 10) reads the tier of the scene's `{boss}` from Superior Lib, so one scene can change its voice with the stakes: three lines guarded by `min 7`, `min 4 max 6` and `max 3` play exactly one. Pair it with `"unless": {"boss_tier": {"min": 1}}` for a boss Lib has not classified.

### Unlock keys

A quest `reward`, dialogue choice, or any other action slot can give the player per-player unlock keys. Superior Lib stores them (`PlayerUnlockApi`); consumers decide what a key opens: Superior Shop gates (see the Shop's `docs/KUBEJS_SHOP_API.md`, "Unlock gates") and Superior Lib recipe locks (`data/<ns>/superior_lib/recipe_locks/*.json`). Operators use `/superior_lib unlock|lock <players> <key>` and `/superior_lib unlocks <player>`.

- `unlock` gives keys. A value is a key string, an object with any of `category` (the Shop's automatic key `category:<id>`), `entry` (`entry:<id>`), `key`, `keys` (an array of strings), and `notify` (default `true`), or an array of those: `{"unlock": ["ignis_wares", {"category": "dragon_eggs"}, {"key": "fishing_tier_1", "notify": false}]}`. `notify: false` anywhere in an array silences the whole action; the Shop prints its notice only for a key that gates Shop content.
- `lock` takes the same forms and removes those keys.
- `unlocked` condition takes the same forms and is true when the player holds every listed key: `{"if": {"unlocked": "fishing_tier_1"}}`.
- Bundled: the `ignis_ashes` quest reward unlocks the Shop's `dragon_eggs` category (a locked teaser until then).

#### Rituals (Summoning Rituals)

A ritual is a recipe, so it unlocks like any recipe lock: the NPC (or a quest reward) gives a key, and a Superior Lib recipe lock names the ritual by recipe ID.

```json
// data/<ns>/superior_lib/recipe_locks/ignis_ritual.json
{"requires": "ritual_ignis", "recipes": ["kubejs:ritual/ignis_heart"],
 "hint": {"translate": "mypack.ritual.locked.ignis"}}
```
```json
// the line or choice where the quest giver tells the player about the ritual (or a quest `reward`)
{"unlock": {"key": "ritual_ignis", "notify": false}}
```

- Give the ritual an explicit ID in its recipe script (`.id('kubejs:ritual/ignis_heart')`); an auto-generated ID is not stable enough to name. A ritual has no result item, so only `recipes` can name it.
- Until the player holds the key, the altar refuses to start that ritual for them (the items come back and the hint shows on the action bar), and JEI and EMI hide the recipe. Unlocking shows it again at once. An altar run without a player (automation) is not gated.

### Fish quests and the fisherman guide (Starcatcher)

With Starcatcher installed, the `fish` item source picks guide fish from Starcatcher's own fish registry (no fish, biome, or rarity is named in Story):

```json
{"mods": ["starcatcher"],
 "item": {"fish": {"rarity": "rare", "dimension": "minecraft:overworld", "fluid": "minecraft:water", "kinds": 3}},
 "reward": {"unlock": {"key": "fishing_tier_3", "notify": false}}}
```

- Fields, all optional: `rarity` (`common` to `legendary`, or an array; trash is never offered), `dimension` (an ID or `ns:*`, matched against the dimensions the fish's dimension restriction accepts), `fluid` (`minecraft:water`, `minecraft:lava`, `minecraft:empty` for open-air void fish), `kinds` (distinct species, 1-3), `quantity` (of each, 1-16), `new` (default true: species the player has not caught come first). Out-of-range values are load errors. Too few eligible species blocks the quest with its own reason.
- With no `location`, each picked fish with a biome restriction gets its own map marker to a matching biome (never an objective). An explicit `location` replaces them.
- Facts in any scene carrying the quest: `{fish}` (every picked fish with counts), and for each kind `n` (1-3) `{fish_n}`, `{fish_n_count}`, `{fish_n_rarity}`, `{fish_n_where}`, `{fish_n_dimension}`, `{fish_n_depth}`, `{fish_n_fluid}`, `{fish_n_when}`, `{fish_n_bait}`, `{fish_n_more}` (every other restriction, other mods' types included), `{fish_n_gear}` (an item whose `starcatcher:modifiers` entry enables the fish's fluid). `{fish_where}` and the other unnumbered facts are kind 1. The restriction facts are Starcatcher's own descriptions, rendered on the client in the player's language (`@fishinfo:` values). Guard a line with `needs` so a scene written for three kinds reads right for one.
- The bundled guide: `scenes/guides/fisherman.json` (any fisherman villager, above the bounty villager), five tiers of three chained quests (`quests/guides/fishing/<rarity>_<n>.json`) plus one repeatable per finished tier. Each tier's last quest gives Starcatcher tackle and the key `fishing_tier_<n>`, which opens that tier's Shop stock and, through `data/superiorstory/superior_lib/recipe_locks/fishing_tier_<n>.json`, crafting and smithing of that tackle. "Got any work?" reaches the Bountiful bounties as before.

### Reputation, weather, moon, loot

- `rep` action: `{"rep": 1}` adds to a counter kept per player for the speaking NPC (its name in lower case), so a scene names nobody; `{"rep": {"id": "guild", "amount": 2}}` names a shared counter instead (amount may be negative). `rep` condition: `{"rep": {"min": 1}}` (a bare number is a minimum), with `max` and `id` optional. Reputation is always between -10 and 10 (the total is clamped when it changes, and a `rep` condition `min`/`max` outside that range is a load error). Use the action in a choice or a quest `reward` (paid at whoever takes the quest in).
- A quest whose `reward` has no `coins`, `give`, or `loot` pays coins by its hardest boss's Superior Lib tier: 25 times the tier squared (tier 1 pays 25, tier 5 pays 625, tier 10 pays 2500), when Superior Shop is present. Author a reward only to change that.
- A scene's `speaker` is the speaking NPC's name (custom name or entity name); write `"speaker"` only to override it.
- `weather` condition: `clear`, `rain`, or `thunder`. `moon` condition: `full_moon`, `waning_gibbous`, `third_quarter`, `waning_crescent`, `new_moon`, `waxing_crescent`, `first_quarter`, `waxing_gibbous`.
- `loot` action (best as a quest `reward`): `{"loot": "minecraft:chests/end_city_treasure"}` rolls any loot table into the player's inventory.

### Choose your reward: reward pools

Bundled quests retain their existing payouts and add a bonus choice: three weighted offers, pick one reward. `superiorstory:quest_basic` serves the courier job, `superiorstory:quest_advanced` serves both shrine contracts, and `superiorstory:quest_elite` serves the remaining boss quests. These pools include all 16 active Superior Affixes orbs and all 28 Superior Gambling boxes/crates, distributed by tier. Advanced rewards can offer a Realmkey; elite rewards can also offer an Exalted Realmkey. Dungeons binds plain keys through its own inventory lifecycle; Story authors no destination NBT. Pool weights affect which offers appear, not Gambling's box contents or Dungeons' destination selection.

Reputation bonuses are optional authored entries, using the existing `if`/`unless` guards. The bundled pools use the speaking NPC's reputation when offers are rolled, after the quest's reputation payout. At reputation 5, basic rewards replace common armor/gem and starter-artifact boxes with rare armor/gem and artifact boxes, and add Ascension/Socketing orbs; advanced rewards replace rare armor boxes with mythical armor boxes and replace rare-gem eligibility with increased epic-gem weight. Realmkey offer weights double at 5; elite premium-box and Reflection weights also double. At reputation 8, stackable orb rewards give two instead of one. Knowledge, Reflection, all boxes/crates, and both Realmkeys always give one. Mutually exclusive guards keep only one version of each item eligible. Offer count remains three and pick count remains one. Authors can omit reputation guards entirely or add an explicit reputation `id` to use a named counter.

A quest reward can let the player pick. Author a pool at `data/<ns>/superiorstory/reward_pools/<name>.json` (the path is the pool ID) and point a `reward_choice` action at it:

```json
{"rewards": [
  {"give": {"item": "minecraft:diamond", "quantity": 3}, "weight": 2},
  {"coins": 400, "text": "400 coins"},
  {"give": "minecraft:golden_apple", "if": {"time": "day"}}
]}
```

```json
{"structure": "cataclysm:burning_arena", "item": "cataclysm:burning_ashes",
 "reward": {"reward_choice": {"pool": "mypack:boss_loot", "count": 3, "pick": 1}}}
```

- An entry is an action map, like a quest `reward`, so anything that can be a reward can be a choice. `weight` (default 1) is its chance of being offered; `text` is its row label (default the first action's own description, such as the item given; a value like `@lang:my.key` translates); `if` / `unless` are checked when the offer is made, so a condition can hide an entry that no longer applies.
- `reward_choice` takes a pool ID, or `{"pool", "count" (default 3, at most 4), "pick" (default 1), "fresh" (default false)}`: `count` distinct entries are rolled by weight and shown as choices, and the player takes `pick` of them. The action also works on a dialogue choice.
- `fresh: true` remembers the last offer per player and pool in persisted data, shared across quests and speakers. The next offer excludes all previous options when enough alternatives are eligible; smaller or gated pools reuse only enough previous options to fill the offer. Closing a pending offer does not reroll it.
- When the action runs inside a conversation (the usual turn-in), the bundled `superiorstory:reward_choice` dialogue takes over until every pick is made, then the conversation continues where it was going. A reward earned outside a conversation waits and opens as narration when the player is free. Item names in the rows have the usual colour and hover tooltip.
- Modules can add entries' actions and conditions through the registry, for example Superior Miapi's `unlock_module`.

### Bounties from NPCs (Bountiful)

With Bountiful installed, an NPC offers a real Bountiful bounty through conversation. Bountiful keeps generation, tracking, expiry, and payout; Story keeps the conversation, the pending offer, the giver link on the item, and the tier gate. The smallest file is `{"npc": "minecraft:villager", "beats": [{"text": {"translate": "superiorstory.bounty.line.looking"}, "bounty": {}}]}`; the bundled `bounty_villager` scene is exactly that, so any villager whose profession matches a loaded decree offers bounties.

- `bounty` line kind, every field optional: `decrees` (a decree ID, an array of them, or `"*"` for every loaded decree; default the speaking villager's profession, e.g. `farmer`, so no decree means the line routes to `none` and logs one warning), `rep` (the giver and reputation ID; default the speaking NPC's name in lower case, so every unnamed farmer is one giver), `refresh` (ticks before an untaken offer is replaced, default 24000). Unknown fields are rejected at load.
- The line routes by state, then by the first objective's type. State: a finished bounty from this giver in the pack goes to `turn_in`; any other unexpired bounty from this giver goes to `active`; no reward this reputation may be offered goes to `none`. Otherwise the route is the objective's type ID as `<namespace>_<path>` (`bountiful_item`, `bountiful_entity`, `bountiful_item_tag`, `bountiful_criteria`, `bountiful_command`, or an addon's type), falling back to `generic`.
- The route continues at a branch named `bounty_<route>` in the current dialogue when there is one, else in the template dialogue `superiorstory:bounty/<route>` (files in `scenes/bounty/`, each with `"trigger": "bounty_template"`, a trigger that never fires), else `superiorstory:bounty/generic`. A pack or addon adds a template for a new objective type by adding that file.
- Variables the line sets: `bounty_rarity`, `bounty_time`, `bounty_objectives`, `bounty_rewards` (joined summaries), and for the first three objectives and rewards `bounty_obj_<n>` / `bounty_rew_<n>` (Bountiful summary), `_amount`, `_name`, and for objectives `_have` (progress) (an `@item:` or `@entity:` value for item and entity entries so the client shows the real name, else the entry's description). A line that `needs` an unset one (say `bounty_obj_2`) is skipped.
- `bounty_accept: true` (on a choice) puts the offered stack in the player's inventory and restarts its timer; `bounty_turn_in: true` or `{"rep": 3}` calls Bountiful's cash-in and then adds reputation with the giver (default the bounty's rarity ordinal plus one). A blocked step (already carrying one, offer gone, expired, unfinished) speaks its reason. Conditions `bounty_ready` (a finished bounty from this giver is in the pack) and `bounty_active` (any) take `true`.
- Any dialogue file may carry `"mods": ["bountiful"]`: it is skipped silently, with no load error, when a named mod is absent. The bundled bounty files use it. Bountiful decrees are named by file, so the Story jar ships `leatherworker` and `cartographer` decrees (data only, reusing the `leatherer` and `mapper` pools) so those professions match by default; add a decree file the same way for any other profession.
- A mob never talks while it is targeting the player or fleeing (villager panic, or a running panic or avoid goal); the Talk prompt is hidden and the key does nothing until it calms down.
- Extra Bounties givers: Deep One (Alex's Caves), the Sculptor (Mowzie's Mobs), and the Priest, Apothecarist, Pyromancer and Cryomancer (Iron's Spellbooks) have their own `bounty_<npc>` scenes naming that mod's Extra Bounties decree. Other mods' Extra Bounties pools are merged into a villager profession's decree by `bounty_decrees/mods/<mod>/<profession>.json` (same base file name, gated by `requires`), so that profession offers them alongside its stock bounties. `mason` and `weaponsmith` have base decrees because Bountiful ships none. Assignments and reasons are in `docs/reports/EXTRA_BOUNTIES_RARITY_2026-09-30.md`.
- Reputation sets the tier: the offered bounty's rarity never exceeds the one for the player's reputation with the giver (Story reputation is -10 to 10 and is passed to Bountiful multiplied by 3, so: common below 2, uncommon at 2, rare at 5, epic at 9, legendary at 10). Story raises every reward entry's `repRequired` in the selected decrees to its own rarity's tier at offer time. Decrees, pools, objective types, and reward types from any Bountiful addon work without Story code.

### Known-structure index

Structure starts that generation already resolved (Superior Worldgen's Surface LOD and real generation, and real chunk loading in any dimension) are recorded per dimension in Superior Locator's `superior_locator_known_structures` saved data, bucketed in 512-block cells. `locate` asks it first, so a search costs no chunk generation; the contract is Superior Lib's `KnownStructureStartsApi`.

### Map markers

Accepting a quest adds a quest-gold marker on the Superior Skylines map (the player's view turns to it first, then a ping). Markers are drawn on the HUD only: dim and unlabeled until looked at or near (within about 24 GUI pixels of the crosshair or 48 blocks), with a thin outlined crest, a soft pulse, and a short scale-in. They are anchored at the structure's height when known. There is no world beam.

### Grandmaster contracts

See [Quest Catalog](docs/reference/QUEST_CATALOG.md) for all 34 fixed quests, their choices, tasks, prerequisites, rewards, and generated quest flows.

Geo Grandmaster, Pyromancer, Cryomancer, and Priest share six repeatable contracts in `scenes/contracts/grandmaster.json`, selected through the optional entity-type entries in `#superiorstory:contract_givers`. There is no cooldown or reputation gate. The shared menu also offers Iron's Spellbooks Bountiful jobs; existing spellcaster bounty scenes remain the fallback when the shared contract scene's required mods are absent. Ordinary Geomancers receive no quest dialogue, and native right-click trading remains available.

The contracts use existing JSON only: locate and mark the specified structure when there is one, explain any summon item by name, and consume the requested item on return. Crafting, summoning, boss kills, and drop provenance are not completion requirements. Shrimple and the Soul Sage raid bounty are fetch quests without a structure marker. The Ominous Grimoire is a rare native drop.

| Contract | Destination | Turn-in |
|---|---|---|
| Something Took the Bait | Suitable water | Raw Prawn |
| Silence the Crypt | Draugr Crypt | Ebony Ingot |
| Shut Down the Centurion | Raldbthar | Dwarven Oil |
| The Orchid Offering | Orchid Shrine | Orchid Queen Carapace |
| Break the Illager Fort | Illager Fort | Totem of Undying |
| The Soul Sage's Grimoire | Native raids | Ominous Grimoire |

Every authored item and entity name in the dialogue uses the existing inline hover references, including named summon items and rewards. Copy lives in `assets/superiorstory/lang/en_us.json`.

`reward_pools/contracts/unique_weapons.json` contains 42 base Simply Swords Unique weapons: the installed 1.70.2 lootable set plus Decaying Relic and Enigma. Transformed/upgraded forms (including awakened Lichblades, transformed relics, Magi weapons, and crafted Dreadtide) are excluded. Every contract, including the Grimoire, offers four equally weighted distinct weapons and grants one, alongside its unchanged coins, reputation, orbs, keys, and lootboxes. `fresh: true` excludes the previous four options across all six contracts and all four speakers. Weapons may return after an intervening offer. The existing Fantasy equipment and armor pools remain available to other authored content.

The separate one-time reputation-8 weapon claim is removed; weapon rewards now require contract completion. Normal and Exalted Realmkey rewards use Superior Dungeons' existing catalog.
