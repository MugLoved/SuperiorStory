# Superior Story Objectives And Reputation Plan

Status: active
Doc Type: execution-plan
Owner: `addons/superior_story`
Authoritative For: quest extra objectives (multi-boss, deliver), reputation, weather and moon conditions, the loot reward, and the content that uses them
Supersedes: none
Superseded By: none
Last Updated: 2026-09-29 America/Denver

## 1. Outcome and scope

The operator asked to add, on top of `SUPERIOR_STORY_MODULAR_AUTHORING_PLAN_2026-09-29.md`: quest chains, NPC reputation, repeatable and rotating quests, multi-boss objectives with one waypoint per structure, a deliver-to-NPC objective (NPC appears in an author-chosen safe structure, glowing gold, spawned at the player), weather and moon conditions, reward variety, and new content. Authorized: implement, test, deploy Story.

Deferred and excluded: boss-tier scaling of coin rewards (a loot table per tier covers the need without new code), a separate item input screen (already deferred in the parent plan).

## 2. Design decisions

- One `also` list on the quest file, not two special cases. Progress is per objective inside the quest instance tag (`objs`), so stored stages, the turn-in check, ally sharing, and quest events are unchanged; the instance only gains `pending` (objectives left).
- Structures for extra objectives reuse the `locate` search (`LocateLine.Search` with a sink), with no quest-profile filter since the author chose them.
- The deliver NPC spawns at the player (nudged 1.5 blocks along their facing when there is room). No safe-spot search.
- Chains and rotation already existed through the `quest` condition, `repeatable`/`cooldown`, and pool targets, so they got documentation and content only.

## 3. Completion matrix

| Requirement | Implementation | Production path | Validation | Delivery |
|---|---|---|---|---|
| Multi-boss objectives, marker per structure | `QuestDef.also`, `QuestObjectives`, `StoryQuests` kill credit | real quest instance tag, real waypoint packets | parse contract test; in-game pending | deployed |
| Deliver NPC at safe structure, glowing gold | `QuestObjectives.spawn`, `deliver` action, gold team | real spawn on structure detection | parse contract test; in-game pending | deployed |
| Quest chains | existing `quest` condition on the quest `if` | `twin_seals.json` gated on `ignis_ashes` | bundled quest parses | deployed |
| Repeatable and rotating quests | existing `repeatable`, `cooldown`, pool `structure` | `sunken_proof.json` | bundled quest parses | deployed |
| Reputation | `rep` action and condition | Wren lines, quest rewards | compile test; in-game pending | deployed |
| Weather and moon | `weather`, `moon` conditions | Wren lines | compile and rejection test | deployed |
| Reward variety | `loot` action | `twin_seals.json` reward | compile test; in-game pending | deployed |
| Content | pool, 2 quests, 2 scenes, 1 edited scene | bundled scenes load | `everyBundledDialogueAndProfileLoads` | deployed |

## 4. Evidence

Story `gradle --no-daemon test`: 33 tests, 0 failures. Deployed `superior_story.jar` SHA-256 `72df6bb9bf59b6b353d063a4bf6fc4330eb3d19e809cae370db3a217ad9243da`, equal to the built jar.

Not run in game: structure detection spawning the NPC, glow and gold outline, the marker swap, boss objective credit, `deliver`, delivery NPC cleanup, extra-objective search through Superior Locator, ally copy. Operator acceptance is pending.
