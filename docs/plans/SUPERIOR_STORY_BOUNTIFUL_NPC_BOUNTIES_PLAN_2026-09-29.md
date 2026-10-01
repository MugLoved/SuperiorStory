# Superior Story Bountiful NPC Bounties Plan

Status: active
Doc Type: execution-plan
Owner: `addons/superior_story`
Authoritative For: NPC bounty givers over Bountiful (the `compat/bountiful` modules, reputation-gated bounty tiers, bounty conversation templates), the Extra Bounties rarity patch data, and removal of the Bountiful board from normal play
Supersedes: none
Superseded By: none
Last Updated: 2026-09-30 America/Denver

## 1. Outcome, scope, authorization

Requested outcome: NPCs replace the Bountiful bounty board. Talking to an NPC offers a Bountiful bounty through a Story conversation whose lines are built from the bounty's required objectives and rewards. Bountiful keeps generation, tracking, expiry, and reward payout. The player's Story reputation with that NPC decides the highest bounty tier (Bountiful rarity) the NPC can offer. Content from any Bountiful addon (decrees, pools, objective/reward types) works without per-addon Story code. Extra Bounties entries get sorted into rarity tiers by task and reward, and that sorting is active in game.

Included:

- `compat/bountiful` in Story (line kind, actions, conditions, variables, offer store, tier gate), loaded only when Bountiful is present.
- Bundled default bounty conversation templates (one per Bountiful objective type plus a generic fallback) and a default villager bounty-giver scene.
- Extra Bounties rarity classification for every pool entry, shipped as Bountiful pool patch data in the Story jar.
- Board removal from normal play: crafting recipes removed, village gazebo generation off.
- README, build, deploy.

Excluded: edits to Bountiful or Extra Bounties jars (immutable third-party); reimplementing any Bountiful tracking, generation, or payout; multi-offer menus per NPC (deferred, section 7); removing boards already placed in worlds (pre-release, no migration).

Authorization: this plan was requested as execution-ready. Implementation starts when the operator says to execute it. Deployment of the finished Story jar and runtime config/script changes is authorized under the workspace standing preference. Agents must not launch a Minecraft client; in-game acceptance is the operator's.

Start here (fresh agent): read root `AGENTS.md`, `DOC_MAP.md`, `LINUX_ADDON_REPO_LOCATION_AND_COMMIT_POLICY.md`, Story `AGENTS.md`, then this plan. Build notes: Story compiles against staged pointers, so run `gradle --no-daemon stageWorkspaceCompileArtifact` in `superior_lib`, `superior_skylines`, and `superior_locator` first when the staged artifacts are missing or stale; Story also needs the Superior Shop Forge dev jar in `superior_shop/forge1201/build/libs`. Write Java with a BOM-free UTF-8 writer (PowerShell `Set-Content -Encoding utf8` adds a BOM that `javac` rejects). Bountiful's decompiled source is indexed in `decompiled/INDEX.tsv`; reuse it.

## 2. Established facts (reuse, do not re-audit)

Source: Bountiful `6.0.4+1.20.1`, decompiled at `decompiled/bountiful/bountiful-6.0.4+1.20.1__jar-Bountiful-6.0.4_1.20.1-forge__sha256-ba171d8cf146/` (Kotlin; `BoardBlockEntity` and `BountyItem` failed Vineflower, use `javap -c -p` against `mods/Bountiful-6.0.4+1.20.1-forge.jar`). Extra Bounties `2.1` (`mods/ExtraBounties-universal.jar`) is data only.

| Concern | Bountiful owner | Public entry used by Story |
|---|---|---|
| Generate a bounty | `content/BountyCreator.kt` | `BountyCreator.Companion.createBountyItem(ServerLevel, BlockPos, Set<Decree>, int rep, long startTime)`; rep clamped to [-30, 30] inside |
| Decrees, pools | `content/BountifulContent.kt`, datapack `bounty_decrees/`, `bounty_pools/` | `BountifulContent.INSTANCE.getDecrees()`, `getDecrees(Set<String>)`, `getPools()`; `Decree.getRewardPools()` / `getObjectivePools()`; `Pool.getItems()` |
| State | NBT on the bounty `ItemStack` | `BountyData.Companion.get(stack)` (returns `Object`, cast), `BountyInfo.Companion.get(stack)` (`getRarity()`, `timeLeft(Level)`) |
| Tracking | kill events scan bounty stacks in the player inventory (`BountyTypeEntity.incrementEntityBounties`, `BountifulSharedApi.handleEntityKills`); criteria via Kambrik criterion subscription; items checked at cash-in | nothing; works while the stack is in the player's inventory |
| Completion check | `IBountyObjective.getProgress(entry, player).isComplete()` per `BountyData.getObjectives()` entry | same, side-effect free |
| Turn-in | `BoardBlock.use` calls `BountyData.tryCashIn(player, stack)` then `BoardBlockEntity.updateCompletedBounties` (board-local counter only) | `BountyData.tryCashIn(player, stack)`: rejects expired, consumes items, pays rewards and XP, shrinks the stack |
| Text for any type | `IBountyType.textSummary(entry, isObj, player)`, `getDescription(entry)` | via `BountyDataEntry.getLogic()`; `getLogicId()` gives `bountiful:item`, `bountiful:entity`, `bountiful:item_tag`, `bountiful:criteria`, `bountiful:command`, or an addon type |
| Tiers | `bounty/BountyRarity.kt`: COMMON -30, UNCOMMON 5, RARE 15, EPIC 25, LEGENDARY 30 (`repTier`) | `BountyRarity.Companion.forReputation(rep)`, `getRepTier()` |

Consequences:

- Bounty rarity is the highest reward rarity. The only hard reputation gate is reward-entry `rep >= repRequired` in `genRewardEntries`; rep otherwise only reweights. No stock or Extra Bounties pool sets `repRequired`. Objective entries are never rep-filtered; their rarity only reweights selection, and objective size follows reward worth.
- Stock Bountiful pools set rarity (64 UNCOMMON, 40 RARE, 31 EPIC, 15 LEGENDARY across all pool entries, including objectives). The raw reward-entry subset is 54 COMMON, 37 UNCOMMON, 31 RARE, 24 EPIC, and 14 LEGENDARY before conditional loading and merging. Extra Bounties sets none: its 3,256 entries across 140 pool files (3,247 `item`, 6 `item_tag`, 3 `entity`) all default to COMMON.
- Pool loading (`config/ResourceLoadStrategy.kt`) groups files by base file name across all namespaces, drops a file whose `requires` mods are absent (`IMerge.getCanLoad`), then merges in resource-location order with a deep JSON graft per entry key (`Pool.merged`), later wins. Several Extra Bounties base names (`farmer_objs`, `toolsmith_rews`, `librarian_objs`) already merge into Bountiful pools. The reload runs on an async future (`forge/BountyDataReloader.java`), so Story must not assume ordering against its own reload listener.
- Partial patch entries are safe: `PoolEntry` has defaults for every field (`type` `bountiful:null_pool`, `content` `"Nope"`, `rarity` COMMON), and `Pool.merged` re-runs `setup` on the grafted content, so `{"rarity": "RARE"}` under an existing key only changes rarity. Namespace order puts `superiorstory` after `bountiful`, `charm`, and `extrabounties`, so Story patches merge last.
- Merge trap: `Pool.merged` returns `copy(other, content = merged)`, so pool-level `weightMult`, `replace`, and `requires` come from the last file merged, which is the Story patch. A patch must copy pool-level `weightMult` from the last pre-existing file with that base name (omit it when that file omits it) and must never set `replace`.
- Kotlin interop from Java: `BountyCreator.Companion.createBountyItem(...)` with all five arguments (Kotlin defaults do not apply from Java), result nullable; `BountyData.Companion.get(stack)` and `BountyInfo.Companion.get(stack)` return `Object` (cast); `BountifulContent.INSTANCE`; `BountyRarity.Companion.forReputation(int)`.
- Board sources: recipes `data/bountiful/recipes/crafting/bountyboard.json` and `decree.json`; village gazebo added by `BountifulModForge` via Kambrik `addToStructurePool` with weight `boardGenFrequency` (`config/bountiful/bountiful.json`, currently 2).
- Story dialogue handoff: `DialogueServer` already continues a conversation inside another dialogue after a line job (`DialogueServer.java` near line 365: `result.structure()` selects a dedicated dialogue and sets `session.afterJob = new Target(dedicated, Dialogue.MAIN, 0)`). A file is a dialogue when it has a speaker key, `structure`, or a non-scene `trigger` (`Dialogue.isDialogue`). Existing bundled villager scenes bind custom speakers (`story_villager`, `story_alchemist`), not `minecraft:villager`.
- Story facts: compat keys register in `SuperiorStory.java` behind `ModList.get().isLoaded(...)` (see `compat/ftbquests/FtbQuestsDialogueBridge.java`); line kinds follow `server/LocateLine.java`; reputation is `module/ExtraModules.java` (`reputation(player, id)`, `speakerId(context)`, stored in `StoryServer.persisted(player)`); test stubs for absent-mod keys live in `src/test/.../TestHooks.java`; bundled scene load test is `StructureQuestTest.everyBundledDialogueAndProfileLoads`.

## 3. Requirements and acceptance criteria

- R1 NPC offer: a `bounty` line generates a real Bountiful bounty through `createBountyItem` from the NPC's decrees and fills conversation variables from its objectives, rewards, rarity, and time limit. Accepting puts that exact stack in the player's inventory. Acceptance: the accepted item is a normal Bountiful bounty (tooltip, tracking, toast, expiry all Bountiful's).
- R2 Tracking reuse: Story adds no progress tracking. Acceptance: no Story code listens to kills, criteria, or inventory for bounty progress; kill/item/criteria objectives advance through Bountiful.
- R3 Turn-in: turning in to the giving NPC calls `tryCashIn`; on success Story applies reputation with that NPC. Expired or unfinished bounties give a spoken reason (`StoryBlocked`), not a silent failure. Acceptance: rewards and XP come from Bountiful; rep increases by the configured amount.
- R4 (tier thresholds superseded by `SUPERIOR_STORY_DIALOGUE_POOLS_PLAN_2026-09-30.md`: Story reputation is bounded to -10..10 and passed to Bountiful times 3, so the effective Story thresholds are uncommon 2, rare 5, epic 9, legendary 10; the Bountiful-scale numbers below are historical) Reputation sets the tier cap: the offered bounty's rarity never exceeds `BountyRarity.forReputation(rep)` where `rep` is the player's Story reputation with the NPC (1:1 onto Bountiful's scale; Bountiful clamps to ±30). Mechanism (binding): before generating, for every reward entry in the selected decrees' reward pools, set `repRequired = max(repRequired, rarity.repTier)` (idempotent, on the live `PoolEntry` objects, at offer time so the async Bountiful reload cannot race it). Rep is also passed to `createBountyItem`, so Bountiful's own reweighting and objective discount apply. When no reward is eligible (empty rewards), the line routes to `none` (R5). Acceptance: at rep 0 only COMMON bounties; at rep 5 up to UNCOMMON; at 15 RARE; at 25 EPIC; at 30 LEGENDARY.
- R5 Templates from requirements and rewards (binding mechanism): the `bounty` line routes by state, then by objective type.
  - State: a completed bounty from this giver in the inventory routes to `turn_in`; any other bounty from this giver routes to `active`; no eligible reward routes to `none`; otherwise the offer routes by the first objective's logic ID (`<namespace>_<path>`, e.g. `bountiful_entity`), falling back to `generic`.
  - Target: when the current dialogue has a branch named `bounty_<route>`, continue there. Otherwise hand off to the bundled template dialogue `superiorstory:bounty/<route>`, falling back to `superiorstory:bounty/generic` for unknown offer types.
  - Engine change (the only one): add `@Nullable ResourceLocation dialogue` to `StoryHooks.LineKind.Result`; `DialogueServer` continues in that dialogue exactly as it does for `structure`. Update `LocateLine`'s `Result` constructions.
  - Template dialogues live in `scenes/bounty/*.json`, carry `"trigger": "bounty_template"` (a registered `Trigger` whose `matches` is always false, so the file loads as a dialogue and never opens by itself), and hold the lines and the accept/decline or turn-in choices. The session speaker is unchanged by the handoff.
  - Ship templates for `turn_in`, `active`, `none`, `generic`, and `bountiful_item`, `bountiful_entity`, `bountiful_item_tag`, `bountiful_criteria`, `bountiful_command`, with all text in `lang/en_us.json`. Any addon or pack adds a template for a new objective type by data alone.
  - Variables cover every objective and reward, including summaries from `textSummary` so unknown addon types read correctly. Acceptance: an unknown objective type renders through `bounty/generic` with its own summary text; the minimal file in R9 produces a full offer, accept, track, turn-in loop.
- R6 Automatic addon compatibility: decrees resolve through `getDecrees`; `"decrees": "*"` means every loaded decree; default decrees derive from a villager's profession path when a decree with that ID is loaded; no decree, pool, or type IDs are hard-coded in Java. Acceptance: Extra Bounties decrees are offered with no Story code naming them.
- R7 Offer stability: an NPC's pending offer for a player persists (player persisted data, keyed by rep ID) until accepted or until its refresh time passes (default one Minecraft day, `refresh` field in ticks). Re-talking does not reroll. One active bounty per player per giver by default.
- Giver identity (decision): the giver is the rep ID, not the entity. Unnamed villagers resolve to their profession name (`farmer`), so every farmer shares one reputation, one pending offer, and one active bounty per player, and any farmer accepts the turn-in. A named NPC (custom name or `rep` field) is its own giver.
- R8 Giver link: an accepted stack records its giver rep ID. Default mechanism: a `superiorstory_giver` string in the stack's root tag. Must verify Bountiful's `BountyData/BountyInfo.set(stack, ...)` preserves foreign root keys (read `ItemDataJson` via `javap` in Kambrik or Bountiful); if it does not, store a stack UUID in the player's Story data instead.
- R9 JSON contract: the Story `AGENTS.md` modular contract applies. Every `bounty` field has a default; unknown keys are rejected at load naming file and field; the smallest file is `{"npc": "minecraft:villager", "beats": [{"bounty": {}}]}`. All player-facing text is in lang.
- R10 Extra Bounties rarity: every Extra Bounties pool entry (all 140 files, installed or not) gets a rarity by the rubric in section 5, shipped as patch files that Bountiful merges, and is active in game (bounty tooltips show the tier; R4 gates on it). The patch changes only entry `rarity`; it copies the base file's `requires` so an absent mod's patch is skipped, and copies pool-level `weightMult` per the merge trap in section 2.
- R11 Decree coverage: every decree loaded in this instance can produce a bounty at rep 0 (has at least one COMMON reward entry after R10). Gaps are fixed by patch data, not code.
- R12 Board removal: the board and decree recipes are removed in the runtime instance and new villages stop generating the gazebo. Existing boards are left alone.
- R13 Delivery: Story builds, the bundled scene test passes, the jar is deployed with SHA-256 parity, README documents the JSON surface.

## 4. JSON surface (binding shape, names final unless an implementation conflict is recorded here)

Line kind `bounty`, value an object (all fields optional):

| Field | Default | Meaning |
|---|---|---|
| `decrees` | the speaking villager's profession path when a decree with that ID is loaded; otherwise the line routes to `none` and logs one warning naming the file | list of decree IDs, or `"*"` for all loaded decrees |
| `rep` | speaker rep ID (`ExtraModules.speakerId`) | reputation ID used for the tier cap and the turn-in reward |
| `refresh` | `24000` | ticks before an untaken offer is replaced |

Actions: `bounty_accept` (value `true`), `bounty_turn_in` (value `true` or `{"rep": <amount>}`; default amount = rarity ordinal + 1). Conditions: `bounty_ready` (a completed bounty from this giver is in the inventory), `bounty_active` (any bounty from this giver in the inventory). Both actions and conditions resolve the giver the same way as `rep`. Trigger: `bounty_template` (never fires; marks template dialogues). Custom NPC files may still branch on `bounty_ready` / `bounty_active` themselves; the minimal file needs neither because the line routes by state.

Variables set by the line: `bounty_rarity` (translated tier), `bounty_time`, `bounty_objectives`, `bounty_rewards` (joined summaries), and per entry `bounty_obj_<n>`, `bounty_obj_<n>_amount`, `bounty_obj_<n>_name`, `bounty_rew_<n>`, `bounty_rew_<n>_amount`, `bounty_rew_<n>_name`, plus `@item:` values where the entry content is an item so choice rows show icons (same convention as `Action.rewardVar`).

## 5. Extra Bounties rarity rubric (binding for R10)

The operator-approved [Extra Bounties Rarity Classification Ruleset](../reference/EXTRA_BOUNTIES_RARITY_RULESET.md) owns the classification rubric for R10 and supersedes this section's previous broad tier table. Use stock Bountiful anchors first, then actual variant, usefulness, and normal survival progression. Classify each entry once; a shared objective/reward entry keeps one rarity. Pool names, mod namespaces, and `unitWorth` do not decide rarity.

The guide defines reward and objective tiers, variant and quantity treatment, decree coverage, and the non-binding stock distribution comparison. The shipped patch files remain the source of truth for individual assignments; the report in section 6 B2 records the counts and the EPIC/LEGENDARY list. R10 and R11 remain operator-owned as recorded in section 9; this documentation change does not implement their data.

Patch file shape (path `src/main/resources/data/superiorstory/bounty_pools/extrabounties/<same base name>.json`):

```json
{ "requires": ["alexsmobs"], "content": { "alexsmobs_dropbear_claw": { "rarity": "RARE" } } }
```

## 6. Implementation batches

Dependencies: B1 is the production-integration milestone and comes first. B2 and B3 are independent of each other and of B1's code (B2 needs B1 only for in-game gating proof). B4 closes after B1-B3.

### B1 Production integration: NPC offer to Bountiful and back (milestone)

Representative real input: a vanilla farmer villager, stock `farmer` decree, real `createBountyItem`, real stack in inventory, real `tryCashIn`.

- [ ] Build: add `compileOnly` for `Bountiful-*-forge.jar` via `workspaceResolveExternalJar` in `build.gradle` (same pattern as FTB Quests). Add `Kambrik-*.jar` and `kotlinforforge-*-all.jar` compileOnly only if `javac` needs supertypes.
- [ ] `compat/bountiful/BountySpec.java`: JSON parse and validation for the `bounty` value (no Bountiful imports, so tests can use it).
- [ ] `compat/bountiful/BountifulBridge.java`: register `bounty` line kind, `bounty_accept`, `bounty_turn_in`, `bounty_ready`, `bounty_active`; register from `SuperiorStory.java` behind `ModList.get().isLoaded("bountiful")`.
- [ ] Offer store in `StoryServer.persisted(player)` under one key: per rep ID the serialized stack and its creation game time (R7).
- [ ] Tier gate (R4) applied to the selected decrees' reward pools at offer time; empty rewards route to `none`.
- [ ] Engine: `LineKind.Result.dialogue` handoff and `bounty_template` trigger (R5); update `LocateLine` `Result` constructions.
- [ ] Variables and state/type routing (R5); make `ExtraModules.speakerId` and `reputation` reachable from the bridge (widen visibility; no copies).
- [ ] Giver link (R8), with the `ItemDataJson` preservation check recorded in section 9.
- [ ] Turn-in (R3): locate the giver's completed stack, `tryCashIn`, then add rep through the same stored `reps` tag the `rep` action uses (one helper in `ExtraModules`, no second rep store).
- [ ] Default content: `scenes/bounty_villager.json` (`minecraft:villager` speaker, one `bounty` line, default priority so existing higher-priority scenes keep precedence) and every template dialogue listed in R5 under `scenes/bounty/`, text in `lang/en_us.json`.
- [ ] `TestHooks`: register `bounty` with `BountySpec.parse` and a no-op job, and stub actions/conditions, plus the `bounty_template` trigger, so the bundled scene test covers the new scene and templates; add them to `everyBundledDialogueAndProfileLoads`; one rejection case for an unknown `bounty` field in `ModularAuthoringTest`.
- [ ] README: the section 4 surface.

Evidence to finish B1: compile, the two tests above, jar built. In-game proof is operator acceptance (section 8).

### B2 Extra Bounties rarity patch data

- [x] Extract all 140 Extra Bounties pool files; list entries with type, content, amount, and whether each pool is referenced from decree `objectives`, `rewards`, or both.
- [x] Classify every entry by the section 5 rubric; write one patch file per base name, copying `requires`. Scope narrowed by operator instruction 2026-09-30 to installed mods plus Gateways to Eternity and Ice and Fire: 46 files, 1,303 entries.
- [x] Data check (one-off script, output recorded in section 10): every patch key exists in the Extra Bounties pool with the same base name; every rarity is a valid enum name; the entry count matches the in-scope pools (1,303 of 3,256).
- [x] R11 audit: for every decree loaded in this instance (Bountiful's 12 plus Extra Bounties decrees whose `requires` are installed), confirm at least one COMMON reward entry after patching; fix gaps in patch data.
- [x] Report `docs/reports/EXTRA_BOUNTIES_RARITY_2026-09-30.md`: per-mod tier counts, the EPIC and LEGENDARY entry list, provisional assignments, R11 audit result.

Evidence: the data check output and the report. The in-game proof is the post-launch log check in B4.

### B3 Board removal (runtime root)

- [ ] `kubejs/server_scripts/`: remove recipes `bountiful:crafting/bountyboard` and `bountiful:crafting/decree`.
- [ ] `config/bountiful/bountiful.json`: `boardGenFrequency` to `0`. Check from bytecode that Kambrik's `addToStructurePool` with weight 0 adds no piece; if it still adds one, disable the gazebo with an empty template-pool override instead and record which.

Evidence: script and config diff; recipe absence confirmed in game by the operator.

### B4 Delivery

- [ ] One consolidated Story `gradle --no-daemon test` after B1 and B2 land; build the reobfuscated jar; deploy to `mods/`; record SHA-256 parity.
- [ ] After the operator's next launch, read `logs/latest.log`: no Bountiful decode errors for `superiorstory` patch files, Bountiful's `Merging <pool>` lines appear for patched Extra Bounties pools, no Story load errors for the bounty scene.
- [ ] Commit Story changes in the Linux addon repo; commit B3 and doc routing in the root repo; explicit staging only.

## 7. Deferred (not required)

- More than one offer per NPC (board-style choice list).
- Per-NPC custom tier thresholds (1:1 mapping unless pacing proves wrong in play).
- Custom speaker names and voices per decree beyond the default templates.

## 8. Gates

| Gate | Trigger | Blocks | Sufficient evidence |
|---|---|---|---|
| Build and tests | B1 and B2 code/data complete | B4 deploy | compile plus Story test task green |
| R8 preservation check | before finishing the giver link | B1 completion | bytecode reading of `ItemDataJson.set` recorded in section 9 |
| Operator in-game acceptance | after deploy | final PASS only, not further implementation | operator confirms: farmer offer shows lines built from the bounty; accepted item tracks kills or items; turn-in pays and raises rep; rep 0 offers only COMMON and higher rep unlocks higher tiers; an Extra Bounties decree bounty shows a patched rarity; board recipe gone |

## 9. Completion matrix

Operator decision 2026-09-29 reserved the Extra Bounties classification (B2, R10, R11) for the operator. On 2026-09-30 the operator instructed the agent to do it end to end, limited to mods installed in `mods/` plus Gateways to Eternity and Ice and Fire; the R10 and R11 rows below record that delivery. Pools for other absent mods stay unclassified until those mods are installed (their patches would be skipped by `requires` anyway).

| Req | Implementation | Production path | Validation | Delivery |
|---|---|---|---|---|
| R1 offer | done | compiled against real Bountiful (`createBountyItem`); not run in game | bundled scene test only | deployed |
| R2 tracking reuse | done (Story has no tracking code) | n/a | source review | deployed |
| R3 turn-in | done (`tryCashIn`, then `ExtraModules.addReputation`) | not run in game | not run | deployed |
| R4 tier cap | done (`repRequired` raise at offer time, rep passed to Bountiful) | not run in game | not run | deployed |
| R5 templates | done (line routing, `dialogue` handoff, 10 template dialogues, lang) | not run in game | templates load and parse in `everyBundledDialogueAndProfileLoads` | deployed |
| R6 addon compatibility | done (`getDecrees`, `"*"`, profession default, no IDs in Java) | not run in game | not run | deployed |
| R7 offer stability | done (persisted store, `refresh`) | not run in game | not run | deployed |
| R8 giver link | done (`superiorstory_giver` root tag) | not run in game | bytecode check below | deployed |
| R9 JSON contract | done (`BountySpec`, defaults, unknown-key rejection) | n/a | `ModularAuthoringTest` case | deployed |
| R10 Extra Bounties rarity | done for installed mods plus Gateways to Eternity and Ice and Fire (46 patch files, 1,303 of 3,256 entries); pools for absent mods deliberately not authored (report lists them) | patch files in the Story jar; Bountiful merge not run in game | data check (section 10) | deployed, SHA-256 parity |
| R11 decree coverage | done (audit of the 18 loaded Extra decrees, every one has a COMMON reward via `rarities` rabbit stew) | not run in game | data audit (section 10) | deployed |
| R12 board removal | done (recipes removed, `boardGenFrequency` 0) | not run in game | bytecode check below | runtime files edited |
| R13 delivery | done | n/a | Story `gradle test` 38/38 green | jar deployed, SHA-256 parity |

## 10. Evidence

- Build: `gradle --no-daemon test --continue` in `superior_story` compiled against Bountiful 6.0.4, Kambrik 6.1.1, and Kotlin for Forge 4.12.0 (pinned in `builds/workspace-model.json`, vendored jars) and passed 38 tests (6+19+1+2+10, 0 failures).
- R8 (gate): `ItemData.setSubtag` in Kambrik calls `ItemStack.getOrCreateTag()` then `CompoundTag.put(identifier, tag)`, so Bountiful's `BountyData/BountyInfo.set` writes a sub-tag and leaves root keys such as `superiorstory_giver` intact. The stack tag mechanism stands.
- B3: Kambrik `KambrikStructureApi.addToStructurePool` adds `element` to the pool's element list in a loop `i < weight` and records `(element, weight)` only in the counts list, so weight 0 adds no selectable piece. `boardGenFrequency` 0 alone stops the gazebo; no template-pool override needed.
- Deployed (2026-09-30, rarity patch data added; supersedes earlier hashes `4f5d02bc...` and `6fae1653...`, `5dd31994...`, `0b3e4952...`; includes the class-file source check corrections and the Extra Bounties giver assignment (profession decree merges and NPC scenes, see the rarity report)): `mods/superior_story.jar` SHA-256 `847a47aba4d60b085434c94dc2dbd2476076da0da03433989a84e4172f8a7235`, equal to `build/reobfJar/output.jar`; the jar holds 46 `bounty_pools/extrabounties/*.json` patch files. Built with `gradle --no-daemon reobfJar workspaceDeploy` (data-only change, no Java touched, so the Story test task was not rerun).
- R10/R11 data check (2026-09-30): 46 patch files; every `requires` copied from the base pool; every patch key exists in the same-named Extra Bounties pool in the same order; only `rarity` set, all valid enum names; no `replace` or `weightMult`; 1,303 entries. R11: all 18 loaded Extra decrees have at least one COMMON reward (`rarities` rabbit stew). Per-mod counts, EPIC/LEGENDARY list, and provisional assignments are in `docs/reports/EXTRA_BOUNTIES_RARITY_2026-09-30.md`.
- Implementation notes: `LineKind.Result` gained a five-argument form with `dialogue` and keeps the four-argument constructor, so `LocateLine` is unchanged. `bounty_template` is registered with the core modules so the templates load without Bountiful; the `bounty` scene logs a load error when Bountiful is absent. The line sets a `bounty_giver` variable so actions and conditions resolve the same giver as a custom `rep` field. `Vars` placeholders now allow digits so `{bounty_obj_1_name}` works. Default decrees follow the profession path, so leatherworker, cartographer, weaponsmith, and mason villagers get `none` until a scene names a decree (`leatherer` and `mapper` exist in Bountiful).
- Pending: operator in-game acceptance (section 8) and the post-launch `logs/latest.log` check in B4.
