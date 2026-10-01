# Superior Story Dialogue Pools And Reputation Range Plan

Status: active
Doc Type: execution-plan
Owner: `addons/superior_story`
Authoritative For: line pools, word pools, pooled-conversation authoring, and the fixed reputation range of -10 to 10 with its Bountiful tier mapping
Supersedes: the reputation scale in `SUPERIOR_STORY_BOUNTIFUL_NPC_BOUNTIES_PLAN_2026-09-29.md` R4 (tier thresholds only; the rest of that plan stands)
Superseded By: none
Last Updated: 2026-09-30 America/Denver

## 1. Outcome and scope

Requested outcome (operator): conversations vary each time the player talks to an NPC. A conversation is an ordered string of pools, each pool is a set of interchangeable lines that say roughly the same thing and share the same `{placeholder}` words, so adding one entry to a pool enriches every conversation that uses it. Word pools give the same variety at phrase level. Entries can be gated by the existing conditions (reputation, time of day, dimension, quest stage and so on). Reputation is bounded to a minimum of -10 and a maximum of 10, and every agent must follow that bound.

Included: line pools, word pools, the `pool` line form, the `{~word}` token, reroll on every conversation, no-immediate-repeat, `random` and `first` pool modes, load-time validation, datapack merge, a starter pool set with a representative migration of bundled dialogues, the reputation range (writer, reader, condition validation), the Bountiful tier mapping change that the range forces, documentation and routing.

Excluded (default, not binding rationale): pick persistence per session or day (operator chose reroll every talk); migrating every bundled line to pools; player-facing UI changes; new conditions (all existing ones already work in pool entries).

Authorization: the operator instructed end-to-end implementation on 2026-09-30. Implementation, related documentation, commits, and Story deployment are authorized. Agents never launch a client; operator in-game acceptance remains separate.

## 2. Binding decisions

- **Reputation range is -10 to 10 inclusive, fixed.** `ExtraModules` owns two constants, `REP_MIN = -10` and `REP_MAX = 10`, and nothing else may hard-code them. `addReputation` clamps the stored result into the range (still the one writer). `reputation` clamps on read, so a value stored by an older build is treated in range without a migration (no migrations by policy). The `rep` condition rejects at load a `min` or `max` outside the range and `min` above `max`, naming the file and field. The `rep` action `amount` is unbounded in JSON; the clamp applies to the total.
- **Bountiful tier mapping.** Bountiful's scale runs -30 to 30 with tiers at 5 (uncommon), 15 (rare), 25 (epic), 30 (legendary). The bound would make rare and above unreachable, so Story passes `storyRep * 3` (constant `BOUNTIFUL_REP_SCALE = 3`, owned by `BountifulBridge`) to `createBountyItem` and to the tier gate. The gate mechanism (`repRequired = max(repRequired, rarity.repTier)`) is unchanged. Resulting Story-rep thresholds: common below 2, uncommon at 2, rare at 5, epic at 9, legendary at 10. `bounty_turn_in` default amount (rarity ordinal plus one, so 1 to 5) is unchanged; the clamp caps the total.
- **Pools are modules, not a parallel engine.** A line pool is another line source resolved where a line's text is picked (`Dialogue.Line.pick`); the `pool` field plugs into the existing line parser next to `text`, so `pace`, `accent`, `choices`, `needs`, `if`, `unless`, `blocked`, and action keys keep working on a pooled line. Entry conditions reuse `Guard` and the condition registry; no per-location copies.
- **Reroll on every conversation.** The pick happens each time a line plays, never cached in the session or the player data. Within one conversation the same pooled line reached twice (a loop through `goto`) also picks again.
- **No immediate repeat.** Story remembers, in memory only, the last entry index picked per player and pool; when at least two entries are eligible the next pick excludes it. Cleared on logout and reload. Nothing is persisted.
- **Modes.** `"mode": "random"` (default) rolls eligible entries by `weight`. `"mode": "first"` plays the first eligible entry in file order, which gives a tiered greeting (rep 8 and above, then night, then default) with no priority field. Both modes apply the no-immediate-repeat rule first: when alternatives are eligible, `first` chooses the first remaining entry.
- **Word pools.** `{~name}` (or `{~ns:name}`) inside any Story-authored text expands to a weighted random eligible entry before the line is sent to the client, so the existing `{variable}` fill still runs on the result. An entry may itself contain `{~other}`; nesting depth is at most 4 and a cycle is a load error. Each occurrence rolls independently.
- **Defaults and derivation.** IDs come from file paths; an unqualified pool ID inside a dialogue means the dialogue's own namespace; `weight` defaults to 1; a bare string entry is the whole minimal entry.
- **Merge.** Same-ID files from several datapacks append in pack order like Minecraft tags; `"replace": true` discards lower packs; an addon adds to a pool by shipping a file.
- **Validate at load.** Unknown keys, empty pools, unknown pool references, nested-word cycles, and bad conditions reject the pool or the referencing dialogue with the file and field named; siblings stay active. Runtime never re-parses JSON. Limit: 512 entries per pool (same as reward pools).
- All player-facing pool text lives in JSON as literal text or `{"translate": "key"}`, and any Story-authored fallback text lives in `assets/superiorstory/lang/en_us.json`.

## 3. JSON surface (target)

```json
// data/superiorstory/superiorstory/line_pools/greeting.json
{
  "mode": "first",
  "lines": [
    {"text": "{~hail}, {player}! Always good to see you.", "if": {"rep": {"min": 8}}},
    {"text": "Night's no time to be wandering, {~friend}.", "if": {"time": "night"}},
    "Well met, {~friend}.",
    "{~hail}. What brings you by?"
  ]
}
// data/superiorstory/superiorstory/word_pools/friend.json
{"words": ["friend", "traveler", {"text": "stranger", "unless": {"rep": {"min": 0}}}]}
// a dialogue
{"npc": "story_villager", "beats": [{"pool": "greeting"}, {"pool": "smalltalk"}, "Fixed line.", {"pool": "farewell"}]}
```

An entry is a string, `{"translate": ...}`, or an object with `text` plus optional `weight`, `if`, `unless`. Pool file fields: `lines` or `words` (one per file kind), `mode` (line pools only), `replace`.

## 4. Requirements and completion matrix

| ID | Requirement | Implementation owner | Production path | Sufficient evidence | Delivery |
|---|---|---|---|---|---|
| R1 | Line pool: `pool` on a line picks one eligible entry per play, weighted, conditions checked at pick time | `dialogue/LinePool`, `Dialogue.parse`/`Line.pick`, `server/LinePools` loader | real dialogue plays a pooled line through `DialogueServer` | one contract test (weights, `if`/`unless`, `first` mode, empty-eligible handling) | implemented, contract PASS, deployed; live play R12 |
| R2 | Conversations string pools with fixed lines in order | same parser, no new structure | bundled dialogue with three pools and a fixed line loads and plays | bundled load test extended to resolve pool references | implemented, bundled load PASS, deployed; live play R12 |
| R3 | Reroll every conversation, no immediate repeat when 2 or more eligible | `LinePools` last-pick map (memory only) | two consecutive plays of one line | same contract test | implemented, contract PASS, deployed |
| R4 | Word pools: `{~name}` expands before send, nested up to depth 4, cycle rejected | `dialogue/WordPool`, `LinePools`, `StoryHooks` text transforms, `StoryScene.Text` payload | a pooled line containing `{~friend}` and `{player}` reaches the client filled | contract test (expansion, nesting, cycle rejection), resolved-text wire round trip | implemented, contract/wire PASS, deployed; live presentation R12 |
| R5 | Datapack merge and `replace` | resource stacks compiled before scenes in `StorySceneLoader.prepare` | two-pack merge | test reuses the pool merge helper or asserts append and replace once | implemented, contract PASS, deployed |
| R6 | Load-time validation with file and field named; valid siblings stay | parsers | bad pool and bad reference skipped, others active | covered by R1/R4 tests plus bundled load test | implemented, contract/bundled load PASS, deployed |
| R7 | Reputation clamped to -10..10 (writer and reader), `rep` condition rejects out-of-range at load | `ExtraModules` constants, `addReputation`, `reputation`, `RepSpec.parse` | real `rep` action and condition on bundled quests and scenes | one contract test (clamp both ends, legacy stored value read in range, condition rejection) | implemented, persisted-tag/condition contract PASS, deployed; live cap R12 |
| R8 | Bountiful tier mapping: pass `rep * 3`; thresholds uncommon 2, rare 5, epic 9, legendary 10 | `BountifulBridge.generate` and a pure `bountifulRep` helper | real `createBountyItem` with a rep-scaled input | one pure-function test of the scale and threshold table; Bountiful generation path unchanged | implemented, scale/threshold contract PASS, deployed; live offers R12 |
| R9 | Starter content: pools `greeting`, `smalltalk`, `farewell`, word pools `hail`, `friend` and similar; migrate the default lines of the villager, cartographer, alchemist dialogues and the `bounty/generic` opener | `data/superiorstory/superiorstory/{line_pools,word_pools,scenes}` | bundled dialogues load and play | bundled load test | 11 line pools, 2 word pools, five dialogue migrations; bundled load PASS, deployed |
| R10 | Documentation and routing: Story `AGENTS.md` binding rule, README JSON surface and the two reputation lines, bounty plan R4 note, `DOC_MAP.md` route | docs | n/a | doc review | complete: existing bounds/bounty guidance reviewed, README and root route updated |
| R11 | Deploy Story, source and deployed SHA-256 parity | canonical addon Gradle | deployed jar | hash equality | deployed, SHA-256 parity PASS (section 8) |
| R12 | Operator in-game acceptance | operator | talk to a bundled NPC repeatedly; check rep-gated and time-gated entries; check a rep 10 cap; check tier unlocks | operator confirms | pending, not a gate on implementation |

One shared proof may support several rows.

## 5. Batches

Real dependency: R1 unblocks R2 to R6 and R9; R4 depends on R1's line-picking point; R7 and R8 are independent of pools. Two connected batches, no per-helper cycles.

### B1 Pools through the production path

- [x] `dialogue/LinePool` and `dialogue/WordPool` records with `parse` (fields, weight, `Guard`, mode, limits, `replace`), following `dialogue/RewardPool.java`.
- [x] Loader and merge for `line_pools` and `word_pools` following the existing resource-stack merge pattern; expose `get(ResourceLocation)`. Pool preparation and scene validation share one reload listener, removing listener-order dependence.
- [x] `Dialogue.Line`: accept `pool` in `LINE_FIELDS` (exclusive with `text`), resolve at pick time; keep every other line field working. Reference validation after the pool catalogue is installed.
- [x] `{~name}` expansion applied wherever a line or choice text is prepared for the client, before `Vars` fill. Selected words travel in the shared text payload, preserving client localization; protocol 7. Existing variable registry now supplies `{player}`.
- [x] Last-pick memory keyed by player and pool, cleared on logout and reload.
- [ ] Representative real input first: convert `villager_prototype.json` greeting to `{"pool": "greeting"}` and confirm it loads and plays through `DialogueServer` before the content pass. **Conversion, production wiring, compilation, and bundled load complete. Live play remains unverified under the client-launch prohibition (R12); independent implementation and delivery continued.**
- [x] Starter pools and the R9 migration.

### B2 Reputation range and tier mapping

- [x] `ExtraModules.REP_MIN` and `REP_MAX`; clamp in `addReputation`; clamp in `reputation`; `rep` condition range validation.
- [x] `BountifulBridge`: `BOUNTIFUL_REP_SCALE`, pass scaled rep, keep the gate; replace the old Bountiful-scale clamp by the scale of the bounded range.
- [x] Doc updates listed in R10.

### B3 Validation and delivery

One consolidated pass after B1 and B2: Story `gradle --no-daemon test` (the narrowest sufficient target set is the new pool contract test, the rep contract test, and `StructureQuestTest.everyBundledDialogueAndProfileLoads`; broaden only on shared-contract failure), then build the reobfuscated jar, deploy, prove hashes. Collect failures and fix them together. No client launch; R12 stays with the operator.

## 6. Tests (proportional)

- `DialoguePoolsTest` (new, one class): weighted pick respects `if`/`unless`, `first` mode order, no immediate repeat with two eligible entries, empty-eligible handling, `{~word}` expansion and nesting, cycle and unknown-reference rejection, merge append and `replace`.
- `ReputationRangeTest` (new, small): clamp at both ends, legacy out-of-range stored value reads in range, `rep` condition rejects out-of-range at load, `bountifulRep` and tier thresholds.
- Extend the existing bundled load test to resolve pool references. No per-pool, per-NPC, or per-entry tests.

## 7. Notes for implementing agents

- Story build notes are in the bounty plan (staged artifacts, Shop dev jar, BOM-free UTF-8 Java writes). Read root `AGENTS.md`, `DOC_MAP.md`, the Linux repository policy, and Story `AGENTS.md` first.
- The current selection code is `server/DialogueServer.java` `select` and `outranks` (dialogue choice by speaker); pools change what a chosen dialogue says, not which dialogue is chosen.
- Do not add reputation aliases, migrations, or a second reputation store.

## 8. Current state

Implementation and production wiring delivered. Pool JSON compiles once on reload; line sources and text transforms register through `StoryHooks`. Word picks preserve their original translation keys in the shared payload. Cycles, excessive depth, and expansions beyond the existing 512-character text bound fail at load. Player/pool selection history is transient. Reputation uses the existing persisted store, bounded on read and write, and Bountiful generation receives the scaled value without replacing its generation or payout path.

Validation on 2026-09-30: 16 focused tests passed (`DialoguePoolsTest`, `ReputationRangeTest`, `StructureQuestTest.everyBundledDialogueAndProfileLoads`, `StorySceneTest`, `DialogueTest`). The scene and dialogue classes cover the changed shared payload/parser contracts. After final review corrected `first` mode to apply no-repeat before ordered selection, all five pool tests passed again; unaffected evidence was reused. Final packaging compiled the added player-name variable source. No Minecraft client was launched.

Delivery: canonical `gradle --no-daemon reobfJar workspaceDeploy` succeeded. Source `build/reobfJar/output.jar` and runtime `/mnt/c/Users/alexh/curseforge/minecraft/Instances/Superior/mods/superior_story.jar` have identical SHA-256:

`e7c183f9f9be393492c77f4cb9c0636afe843c5ffd2a79e04e109e00de79d243`

Exactly one Story runtime jar is present. No persistent task-owned Gradle/Kotlin processes remain. R12 and the representative live-play check remain with the operator: repeated NPC talks, reputation/time gates, the reputation cap, and bounty tier unlocks are unverified in game.
