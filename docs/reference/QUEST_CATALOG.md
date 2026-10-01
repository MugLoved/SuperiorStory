# Superior Story Quest Catalog

Status: active
Doc Type: status-matrix
Owner: `addons/superior_story`
Authoritative For: current bundled quest inventory and its task, choice, prerequisite, and reward reference; JSON and runtime remain authoritative for behavior
Supersedes: none
Superseded By: none
Last Updated: 2026-09-30 America/Denver

Superior Story bundles **34 fixed quests: 8 adventures and errands, 6 repeatable combat contracts, and 20 fishing quests**. This reference also covers generated structure expeditions, Bountiful jobs, and related conversations. It describes authored behavior, not completed in-game acceptance.

Quest IDs below omit the common `superiorstory:` namespace. Sources: [quest JSON](../../src/main/resources/data/superiorstory/superiorstory/quests), [dialogue scenes](../../src/main/resources/data/superiorstory/superiorstory/scenes), [structure profiles](../../src/main/resources/data/superiorstory/superiorstory/structures), and [English text](../../src/main/resources/assets/superiorstory/lang/en_us.json).

## Shared quest rules

- **Item hand-ins** consume the requested items. Existing items count: crafting, summoning, visiting the suggested destination, and a fresh boss kill are not independently required when an item is the main objective. Extra objectives still apply.
- **Boss quests without an item** require recorded boss defeat and completion of any extra objectives before turn-in. An exact structure supplies its profile's first boss when JSON omits a boss ID.
- **Repeatable** means available again after turn-in. `1d` is **24 real-world hours**, not one Minecraft day. All other fixed quests are one-time unless marked repeatable below.
- **Reputation** is with the speaking NPC by default, bounded to -10 through 10. The listed amounts are explicit quest rewards; a dialogue's good/bad outcome does not itself specify a reputation payout.
- Location offers supply directions; accepting installs a quest waypoint when Superior Skylines is present. Searches can fail when no suitable target is found. Repeatability does not promise that every search will find another destination.

## Adventure and errand quests

Tobin, Wren, and Maren are authored speaker roles: NPC scoreboard tags `story_villager`, `story_cartographer`, and `story_alchemist`, respectively. They do not bind every ordinary villager.

### Ignis Ashes

**ID:** `ignis_ashes` · **Giver:** Maren · **Availability:** one-time, no quest prerequisite.

- **Task:** hand over 1 Burning Ashes. Maren can locate Burning Arena; the profile identifies Ignis, but completion checks the ashes rather than a recorded kill.
- **Choices:** “I already have them” hands them in immediately; “Where do I find it?” searches, then “I'll go” accepts or “Not for me” declines; “Not now” exits.
- **Reward:** 3 diamonds, 250 coins, Dragon Eggs shop-category unlock, and choose 1 of 3 **elite** rewards.
- **Progression:** turn-in opens Twin Seals.

### Twin Seals

**ID:** `twin_seals` · **Giver:** Maren · **Availability:** one-time, after Ignis Ashes turn-in.

- **Tasks:** defeat Ancient Remnant at Cursed Pyramid; defeat Maledictus at Frosted Prison; report to temporary village courier **Sealwright Orin**. All objectives are required; the courier report is not guarded by kill order. No physical seal item is requested.
- **Choices:** ask to break the seals, locate the destinations, then accept or decline; Orin offers “The seals are broken. Take the word to Maren” or “Not yet”; Maren offers “It is done” for turn-in or “Not now.” Turn-in remains blocked until all objectives are complete.
- **Reward:** 1,500 coins, a roll of `minecraft:chests/end_city_treasure`, +2 reputation, and choose 1 of 3 elite rewards.
- **Progression:** turn-in opens Three Deaths.

### Three Deaths

**ID:** `three_deaths` · **Giver:** Maren · **Availability:** one-time, after Twin Seals turn-in.

- **Tasks:** defeat Harbinger at Ancient Factory, Scylla at Acropolis, and Leviathan at Sunken City; return to Maren.
- **Choices:** “I'll hunt all three” locates the targets, then accept or decline; “It is done. All three” attempts turn-in; “Not now” exits.
- **Reward:** a roll of `minecraft:chests/bastion_treasure`, +3 reputation, and choose 1 of 3 elite rewards.
- **Follow-up:** Maren's epilogue is conversation only. Reputation 5 adds a friendly line; no further quest or payout.

### Courier Letter

**ID:** `courier_letter` · **Giver:** Tobin · **Availability:** one-time, no quest prerequisite.

- **Tasks:** accept Tobin's errand and receive paper; find temporary village recipient **Innkeeper Aldric**; hand over 1 paper; return to Tobin and report delivery. The paper is an ordinary item, not a uniquely tracked letter.
- **Choices:** request a safe errand, locate the recipient, then “I'll carry it” accepts or decline; Aldric offers “Here it is, sealed and unopened” or “Not yet”; Tobin offers “It's delivered” for turn-in.
- **Reward:** 150 coins, +1 reputation, and choose 1 of 3 **basic** rewards.

### Shrine for Tobin

**ID:** `shrine_for_tobin` · **Giver:** Tobin · **Availability:** one-time; unavailable after Shrine for Wren is turned in. Offer dialogue also avoids taking both shrine jobs at once.

- **Task:** defeat Kobolediator at Kobolediator Shrine and report to Tobin. Destroying shrine blocks is not checked.
- **Choices:** choose the desert shrine errand, locate it, then accept or decline; report that the champion no longer threatens the fields once boss defeat is recorded.
- **Reward:** 16 bread, +2 reputation, and choose 1 of 3 **advanced** rewards.
- **Consequence:** Wren acknowledges choosing Tobin's bread over her coin; her competing shrine quest becomes unavailable after completion.

### Shrine for Wren

**ID:** `shrine_for_wren` · **Giver:** Wren · **Availability:** one-time; unavailable after Shrine for Tobin is turned in. Offer dialogue also avoids taking both shrine jobs at once.

- **Task:** defeat Kobolediator at Kobolediator Shrine and report to Wren. Dialogue asks that carvings survive, but block preservation is not checked.
- **Choices:** ask for paid desert work, request a map, then accept or decline; report “The champion is dead, and the carvings are safe” once boss defeat is recorded.
- **Reward:** 300 coins, +2 reputation, and choose 1 of 3 advanced rewards.
- **Consequence:** Tobin acknowledges choosing Wren's coin over his bread; his competing shrine quest becomes unavailable after completion.

### Sunken Proof

**ID:** `sunken_proof` · **Giver:** Wren · **Availability:** repeatable, 24-hour cooldown, no quest prerequisite.

- **Task:** defeat Leviathan at Sunken City and report back. “Proof” is recorded boss defeat, not a physical hand-in item.
- **Choices:** ask for paid work in deep water, locate the city, then “I'll dive” accepts or decline; “The deep is quiet now” turns it in after the kill.
- **Reward:** +1 reputation, choose 1 of 3 elite rewards, and **derived boss-tier coins** as described below.

### Abyssal Offering

**ID:** `abyssal_offering` · **Giver:** Wren · **Availability:** one-time, no quest prerequisite.

- **Task:** hand over 1 Tidal Claws. Wren locates Sunken City and describes summoning Leviathan with an Abyssal Sacrifice; the actual completion check is possession of the claws.
- **Choices:** ask about the stirring Abyss altar, request a deep location, then “I'll fetch it” accepts or decline; returning dialogue hands over the claws or lets you postpone.
- **Reward:** +1 reputation, choose 1 of 3 elite rewards, and derived boss-tier coins.

## Repeatable combat contracts

[Shared contract dialogue](../../src/main/resources/data/superiorstory/superiorstory/scenes/contracts/grandmaster.json) binds **Geo Grandmaster, Pyromancer, Cryomancer, and Priest** through [the contract-giver entity tag](../../src/main/resources/data/superiorstory/tags/entity_types/contract_givers.json). All six contracts are repeatable with **no cooldown or reputation gate**. They share per-player quest progress across these NPCs.

**Shared choices:** show contracts; bring back requested items; show Bountiful bounties; leave. Contract selection separates structure jobs, summoned creatures, and the Soul Sage job. Each contract page allows item hand-in, accepting the job (or locating and then accepting a structure job), an active-job reminder, or going back. Items can also be submitted from the shared hand-in branch. Searches that fail offer a return to the menu or exit.

**Shared reward:** every completion gives **+2 reputation** and offers **4 distinct random base Simply Swords Unique weapons; choose 1**. The [42-weapon pool](../../src/main/resources/data/superiorstory/superiorstory/reward_pools/contracts/unique_weapons.json) includes starting Lichblade/relic forms, Enigma, and Decaying Relic; upgraded, transformed, and awakened forms are excluded. The previous four offers are excluded from the next completion across all six contracts and all four speakers; older weapons may return later. Pending choices persist and cannot be rerolled by closing dialogue. The former one-time reputation-8 weapon claim is removed.

Every task below consumes **1 requested item**. The fights and summon steps explain intended acquisition; they are not additional completion checks.

| Quest ID and title | Task and intended acquisition | Fixed rewards in addition to the shared weapon choice and +2 reputation |
|---|---|---|
| `contracts/bait` — Something Took the Bait | Bring Raw Prawn. Dialogue explains using a Crayfish Bait Bucket in suitable water to lure a crayfish. No structure marker. | 100 coins; 2 Orbs of Infusion; Rare Boots Loot Box. |
| `contracts/crypt` — Silence the Crypt | Bring Ebony Ingot. Locates Draugr Crypt and names Draugr Overlord as the target. | 250 coins; Realm Key; 2 Orbs of Ascension. |
| `contracts/centurion` — Shut Down the Centurion | Bring Dwarven Oil. Locates Raldbthar and names Dwarven Centurion as the target. | 300 coins; Realm Key; 2 Orbs of Reforging. |
| `contracts/orchid` — The Orchid Offering | Bring Orchid Queen Carapace. Locates Orchid Shrine; instructs using Orchid Heart on its altar to summon Queen of Orchid. | 350 coins; Realm Key; 2 Orbs of Socketing. |
| `contracts/fort` — Break the Illager Fort | Bring Totem of Undying. Locates Illager Fort; names the evoker and warns of pillager/vindicator guards. | 300 coins; Realm Key; Rare Chestplate Loot Box; 2 Orbs of Ascension. |
| `contracts/grimoire` — The Soul Sage's Grimoire | Bring Ominous Grimoire from Soul Sage raids. Dialogue warns of a rare drop and other raid enemies. No structure marker. | 1,000 coins; Exalted Realm Key; 4 Orbs of Exaltation; Mythical Chestplate Loot Box. |

## Fisherman progression

[Fisherman dialogue](../../src/main/resources/data/superiorstory/superiorstory/scenes/guides/fisherman.json) binds ordinary fisherman villagers when Starcatcher is installed. All 20 IDs below live under `guides/fishing/`.

**Choices:** “Anything you need caught?” offers work when the newest quest is not active; “About your fish...” opens the hand-in branch when it is active; “Got any work?” opens Bountiful jobs; “Just passing by” exits. On an offer, “I'll catch them” accepts or “Not today” declines. Continuing the matching praise line hands over the requested fish and pays the reward; missing items block hand-in.

**Tasks:** the offer selects specific species from Starcatcher's fish catalogue, preferring fish the player has not caught. It shows the selected fish, quantities, and hints from their fishing rules. Bring those exact items; any fish of the same rarity is not sufficient. Fish are consumed, but a fresh catch after acceptance is not required. These quests grant no explicit reputation.

**Prerequisites:** each numbered quest requires turn-in of the preceding numbered quest. The first quest of a new rarity requires the previous rarity's third quest. A rarity's `_repeat` quest opens after its own third quest; it repeats on a **24-hour cooldown** and is not required to advance. Numbered quests are one-time.

| Quest suffix | Requested fish | Reward |
|---|---|---|
| `common_1` | 1 common fish | 40 coins. |
| `common_2` | 1 common fish | 60 coins. |
| `common_3` | 2 of one common species | Copper Hook; 8 Worms; unlock `fishing_tier_1`. |
| `common_repeat` | 1 common fish | 30 coins. |
| `uncommon_1` | 1 uncommon fish | 80 coins. |
| `uncommon_2` | 1 each of 2 uncommon species | 120 coins. |
| `uncommon_3` | 1 each of 2 uncommon species | Steady Bobber; Stone Hook; Humble Skin Smithing Template; unlock `fishing_tier_2`. |
| `uncommon_repeat` | 1 uncommon fish | 60 coins. |
| `rare_1` | 1 rare fish | 150 coins. |
| `rare_2` | 1 each of 2 rare species | 200 coins. |
| `rare_3` | 1 each of 3 rare species | Heavy Hook; Clear Bobber; Seeking Worm; Amethyst Hook; unlock `fishing_tier_3`. |
| `rare_repeat` | 1 rare fish | 100 coins. |
| `epic_1` | 1 epic fish | 250 coins. |
| `epic_2` | 1 epic fish | 350 coins. |
| `epic_3` | 1 each of 2 epic species | Golden Bobber; Meteorological Bait; Cloud Bobber; unlock `fishing_tier_4`. |
| `epic_repeat` | 1 epic fish | 175 coins. |
| `legendary_1` | 1 legendary fish | 400 coins. |
| `legendary_2` | 1 legendary fish | 600 coins. |
| `legendary_3` | 1 legendary fish | Legendary Bait; Sky Skin Smithing Template; unlock `fishing_tier_5`. |
| `legendary_repeat` | 1 legendary fish | 300 coins. |

Common, uncommon, and rare species are filtered to **Overworld water**. Epic species may qualify from **Overworld or Nether, water or lava**. Legendary species have no authored dimension/fluid filter; their own Starcatcher restrictions still apply. Tier rewards write shared Superior Lib unlock keys used by gated Shop stock and recipes.

## Adventure reward pools

Every basic, advanced, or elite choice above rolls **3 weighted distinct entries; choose 1**. These pools do not use the combat contracts' previous-offer exclusion.

| Pool | Possible reward families and reputation effects |
|---|---|
| [Basic](../../src/main/resources/data/superiorstory/superiorstory/reward_pools/quest_basic.json) | Infusion, Renewal, Imbuement, and Dissonance orbs; Create, food, bookshelf, artifact, gem, ammunition, and armor boxes. At reputation 5, selected boxes improve and Ascension/Socketing become eligible; at 8, eligible orb rewards become pairs. |
| [Advanced](../../src/main/resources/data/superiorstory/superiorstory/reward_pools/quest_advanced.json) | Ascension, Socketing, Severing, Reforging, Fate, Rebirth, Sorcery, Binding, and Knowledge orbs; artifacts, gems, rare ammunition/armor, and Realm Keys. At reputation 5, selected armor becomes mythical and gem/key offerings improve; at 8, eligible orb rewards become pairs. |
| [Elite](../../src/main/resources/data/superiorstory/superiorstory/reward_pools/quest_elite.json) | Exaltation, Divinity, and Reflection orbs; legendary gems/ammunition, mythical armor, Realm Keys, and Exalted Realm Keys. At reputation 5, selected rare rewards receive higher weights; at 8, Exaltation/Divinity rewards become pairs. |

Sunken Proof and Abyssal Offering have no fixed `coins`, `give`, or `loot` action, so the shared runtime also pays **25 × highest quest boss tier²** coins when a classified tier and Superior Shop are available. The random reward choice does not suppress that derived payment. Loot-table rewards are rolled items, not guaranteed copies of every entry in a chest table.

## Generated structure expeditions

Tobin offers profiled destinations, and Wren can request a general structure lookup. Their JSON uses `accept: true` without a fixed quest ID, creating a quest for the located structure rather than adding another fixed quest file.

- **Choices:** ask about trouble or exploration; accept the location, request another, or decline. Cataclysm destinations have a dedicated warning conversation, including a gentler-destination option; Sunken City has a dedicated dive warning.
- **Tasks:** visit the accepted structure and, when its profile has a boss, defeat a qualifying boss and report back. Return dialogue can show completion, progress, reminders, or encouragement. Tobin's authored completion button specifically requires the boss-defeated stage.
- **Rewards:** generic quest completion has no fixed item or coin payout. Tobin's bread and warm-bed promise is dialogue flavor; it does not grant a bed, lodging service, or bread stack. The separately authored shrine and courier quests do have real rewards.
- **Repeatability:** another expedition can be offered after completion, but searches exclude already offered locations. The number of possible expeditions depends on profiles and discoverable structures.

## Generated Bountiful jobs

These are generated jobs, not additional fixed Story quest files. [Bounty dialogue templates](../../src/main/resources/data/superiorstory/superiorstory/scenes/bounty) handle item, item-tag, entity, criteria, and command objective types, plus a generic fallback.

- **Givers:** profession-matched villagers; Deep One; Sculptor; Apothecarist, Pyromancer, Cryomancer, and Priest. The shared combat-contract menu also exposes Iron's Spellbooks bounties, including through Geo Grandmaster.
- **Choices:** an offer shows tasks, rewards, rarity, and time remaining; “I'll take it” accepts or “Not now” declines. An active job shows progress. A ready job offers “Here it is” to cash in or “Not yet” to postpone. No eligible work produces the no-work dialogue.
- **Tasks and payout:** complete the exact generated objectives before expiry. Bountiful owns tracking and pays the generated reward list; Story adds default reputation equal to the bounty rarity ordinal plus one. These jobs do **not** grant the contracts' four-way Unique weapon choice.
- **Tier gates:** common below reputation 2; uncommon at 2; rare at 5; epic at 9; legendary at 10. Repeat offers are generated through the existing refresh/active-job rules, not a fixed Story quest cooldown.
- **Pool data:** Story's 46 bundled bounty-pool JSON files supply generation entries and rarity patches; they do not represent 46 separately named quests. See [Extra Bounties assignments](../reports/EXTRA_BOUNTIES_RARITY_2026-09-30.md) for mod/profession routing.

## Related conversations without separate quests

- **Altar of Fire:** place Burning Ashes or leave. Story delegates to the altar's native block interaction; no extra quest or Story payout.
- **Altar of Abyss:** explains the Abyssal Sacrifice ingredients when missing; with an offering, lay it down or leave. Delegates to native block interaction; no extra quest or Story payout.
- **Intro:** onboarding scene ends with the Skill Tree handoff, not a quest reward. The built-in first-join intro remains default unless a scene declares the first-join trigger.
- **Example dialogue:** hear the warning, agree to investigate, refuse, or leave. Emits dialogue outcomes but authors no quest acceptance or reward.
- **Return, Nether warning, and epilogue scenes:** react to location/progress/reputation or offer another lead; they do not add fixed quest definitions.
