# Extra Bounties Rarity Classification Ruleset

Status: active
Doc Type: authoring-guide
Owner: `addons/superior_story`
Authoritative For: classifying Extra Bounties pool entries into Bountiful rarity tiers
Supersedes: classification rubric formerly in section 5 of `docs/plans/SUPERIOR_STORY_BOUNTIFUL_NPC_BOUNTIES_PLAN_2026-09-29.md`
Superseded By: none
Last Updated: 2026-09-30 America/Denver

## Purpose and scope

Use stock Bountiful entries as anchors, then classify modded entries by their actual variant, usefulness, and normal survival progression. The goal is for Extra Bounties rewards and objectives to feel consistent with Bountiful's existing tiers without making every crafted item, piece of equipment, or dimension resource rare.

This guide records the operator-approved ruleset. It covers all Extra Bounties pool entries, including content for mods that are not installed. It does not itself assign entries. The 2026-09-30 assignments for installed mods plus Gateways to Eternity and Ice and Fire ship as patch data in Story and are summarized in the [rarity report](../reports/EXTRA_BOUNTIES_RARITY_2026-09-30.md); pools for other absent mods stay unclassified until those mods are installed. Implementation, patch delivery, and decree coverage are tracked in the [NPC bounty plan](../plans/SUPERIOR_STORY_BOUNTIFUL_NPC_BOUNTIES_PLAN_2026-09-29.md) (R10, R11).

## Verified baseline

The research used the installed Bountiful `6.0.4+1.20.1` and Extra Bounties `2.1` artifacts. Extra Bounties has 3,256 entries in 140 pool files: 3,247 item entries, 6 item-tag entries, and 3 entity entries. None declares rarity, so all default to COMMON.

Bountiful sets the generated bounty's rarity to the highest selected reward rarity. Objective rarity changes selection weight; it does not enforce a maximum objective tier or set the bounty's displayed rarity. Classifying objectives improves their selection frequency but cannot guarantee that every COMMON bounty has easy objectives.

Stock classifications are broader than a strict progression ladder. Plain iron armor is COMMON, diamond armor is EPIC, and netherite armor is LEGENDARY. Ghast tears can be UNCOMMON rewards. Some level-I enchanted books are COMMON. These are observed stock choices, not proposed Extra Bounties assignments.

## Reward tiers

The stock examples below are verified reward entries. The extension rules are the approved authoring policy for modded equivalents.

| Tier | Stock Bountiful reward anchors | Rule for modded equivalents |
|---|---|---|
| COMMON | Plain iron and leather armor, basic food, bookshelves, repeaters, some level-I enchanted books | Routine resources, ordinary food, basic components, inexpensive equipment around iron strength. Processing or crafting alone does not raise rarity. |
| UNCOMMON | Iron shovel and hoe, shield, observer, fisherman saddle, ghast tear | Modest upgrades, specialized components, uncommon renewable drops, simple machinery, and resources needing limited exploration. |
| RARE | Ordinary ender pearl, phantom membrane, compass, spyglass, golden horse armor | Meaningful utility items, dangerous-mob drops, intermediate machinery, and components requiring several production steps. |
| EPIC | Diamond armor, ordinary name tag, nautilus shell, sponge, experience bottle | Diamond-class equipment, strong exploration rewards, substantial curios, advanced machinery, and valuable miniboss rewards. |
| LEGENDARY | Netherite armor, Mending book, totem of undying, heart of the sea | Netherite-class equipment, major boss rewards, exceptional permanent benefits, and final-tier machinery or materials. |

## Classification order

Apply these rules in order. Keywords and mod namespaces may help find candidates, but they do not decide rarity.

1. **Reuse matching stock classifications.** Match the entry's objective or reward role, item, meaningful NBT, and comparable quantity. Use the closest ordinary stock entry. Do not generalize a special bargain: the ordinary ender pearl reward is RARE, while a discounted pearl offer is LEGENDARY. Stock classifications can also differ by role or profession; do not turn one occurrence into a universal item rule.
2. **Use functional equivalents.** Compare armor with armor, machines with machines, and consumables with consumables. An ordinary modded helmet around iron strength belongs near COMMON. Being modded, named, or crafted does not by itself justify RARE.
3. **Judge normal survival acquisition and usefulness.** Consider required progression, danger, exploration, processing, and lasting benefit. Use an ordinary attainable survival route, including relevant pack recipe changes when known. Nether origin alone does not imply RARE, and End origin alone does not imply EPIC. Renewability does not erase a boss or advanced-production requirement. An established endgame farm is not an early-game acquisition route.
4. **Classify the actual variant.** NBT can identify a materially different reward: enchantments, tool materials, stored fluids, gateway definitions, or ability effects. Cosmetic names alone do not justify a higher tier. Item tags also need review of the allowed contents and the bounty type's matching or payout behavior; the tag name is not sufficient evidence.
5. **Handle objective effort and shared entries.** For objective-only entries, use the objective tiers below. When one pool entry serves both objectives and rewards, it must keep one rarity. Use the higher justified acquisition or value anchor. This rule does not require every separate entry with the same item ID to share a rarity when its role, variant, quantity, or bargain terms differ.
6. **Treat quantity as a modifier, not a formula.** More logs remain COMMON. Large bundles of valuable equipment or materials, or filled containers, need judgment about the full authored amount range. Do not promote everything with a high maximum amount. Each entry receives one static rarity, not a tier calculated from its eventual rolled amount.

Do not infer rarity from `unitWorth`. In the researched Extra Bounties artifact, 3,039 entries use 1000, while the Artifacts pool uses 100. These numbers are inputs to Bountiful's exchange calculation, not a consistent measure of progression or player value.

## Objective tiers

Reuse a comparable stock objective first. Where no suitable stock anchor exists, apply this effort rubric.

| Tier | Objective effort |
|---|---|
| COMMON | Routine collection, common mobs, ordinary farming, or basic production. |
| UNCOMMON | Specialized collection, limited biome travel, light combat, or simple processing. |
| RARE | Dangerous targets, substantial exploration, or several production steps. |
| EPIC | Miniboss requirements, advanced progression, or long production chains. |
| LEGENDARY | Major boss requirements or sources available only at endgame progression. |

Dimension access is evidence about effort, not an automatic tier. Likewise, a resource's rarity as a reward does not automatically determine its stock objective rarity: Bountiful has a COMMON diamond objective. Preserve role-specific stock anchors rather than forcing a single global item ranking.

## Examples and variant review

These are recommended first-pass assignments for entries present in Extra Bounties. They are authoring judgments, not shipped classifications.

| Entry | Recommended treatment |
|---|---|
| Create zinc ingot and ordinary cogwheel | COMMON as routine material and basic component. |
| Create brass ingot | UNCOMMON as a processed progression component. |
| Create precision mechanism | RARE as a component requiring several production steps. |
| Alex's Mobs dropbear claw | RARE, consistent with the bounty plan's existing example. |
| Gateway pearl | Classify the referenced gateway's waves, rewards, and utility. Size alone is insufficient. |
| Tinkers filled tank | Classify the tank together with its actual stored fluid and amount. |
| Artifact or ability totem | Classify the actual benefit and acquisition route; do not assign every curio or totem EPIC. |

Extra Bounties contains 225 gateway pearl entries with different NBT and 128 Tinkers entries with NBT. It also includes ability totems and enchanted books with specific effects. A classifier that looks only at the item ID would collapse distinct rewards into one tier.

Default first-pass automation may use known item families and keywords to propose tiers. Review every EPIC and LEGENDARY candidate and uncertain boundary against the relevant content. When acquisition or effect is unknown, record the unresolved classification rather than inventing a source or silently treating the entry as COMMON.

## Decree coverage and distribution

Every decree loaded in this instance must retain at least one valid COMMON reward after classification and pool merging, as required by R11. Audit the decree's full reward set, including shared pools and conditional loading. Do not downgrade premium rewards merely to fill a COMMON slot.

The shared Extra Bounties `rarities` pool contains rabbit stew, which stock Bountiful rates COMMON. Preserving that classification supports reputation-zero offers for decrees that reference the pool. This is a coverage candidate, not proof that every loaded decree is covered. The coverage audit remains necessary when the patch data is authored.

The raw stock reward-entry catalog provides the following non-binding comparison before conditional loading and pool merging:

| Tier | Reward entries | Approximate share |
|---|---:|---:|
| COMMON | 54 | 34% |
| UNCOMMON | 37 | 23% |
| RARE | 31 | 19% |
| EPIC | 24 | 15% |
| LEGENDARY | 14 | 9% |

Use this spread as a sanity check, not a quota. The stock counts 64 UNCOMMON, 40 RARE, 31 EPIC, and 15 LEGENDARY describe all pool entries above COMMON, including objectives; they are not reward-only counts. Different mods have different content mixes, so equal per-mod proportions are not required.

Entry proportions are not offer probabilities. Bountiful's base rarity weights are 1024, 512, 256, 128, and 6 respectively; reputation and entry or pool weight multipliers further affect selection. Keep LEGENDARY exceptional without promoting or demoting entries just to hit a percentage.

## Applying the rules

The shipped patch data, when authored, records the final per-entry assignments. Use the patch contract in the bounty plan: patch existing keys under the same pool base name, change only entry `rarity`, copy `requires`, preserve the appropriate pool-level `weightMult`, and never set `replace`.

Rarity patches change eligibility under Story's reward gate and Bountiful's selection weights. They do not rebalance exchange values or quantities. For example, Extra Bounties assigns the totem of undying a `unitWorth` of 150; making it LEGENDARY does not change that cost. Exchange-value tuning and strict objective difficulty caps are separate behavior changes outside this classification task.

Creating this guide does not complete the per-entry classification, R10 patch delivery, R11 coverage audit, or in-game acceptance recorded in the plan.

## Sources

- [NPC bounty plan](../plans/SUPERIOR_STORY_BOUNTIFUL_NPC_BOUNTIES_PLAN_2026-09-29.md): implementation scope, patch merge contract, coverage requirement, and current completion matrix.
- Installed runtime artifact `mods/Bountiful-6.0.4+1.20.1-forge.jar`, SHA-256 `ba171d8cf146faa138563b4b7114742e1589fc444fce7503a1779eb80a8a4765`: stock pool entries and reward anchors.
- Installed runtime artifact `mods/ExtraBounties-universal.jar`, version 2.1, SHA-256 `f220af555bc7b5922b83ba67062e6f0428435a1c613d44e77f5fdb6eb3314674`: pool counts, variants, amounts, decree references, and exchange values.
- Verified Bountiful source under runtime `decompiled/bountiful/bountiful-6.0.4+1.20.1__jar-Bountiful-6.0.4_1.20.1-forge__sha256-ba171d8cf146/`: `io/ejekta/bountiful/bounty/BountyRarity.kt`, `content/BountyCreator.kt`, and `data/PoolEntry.kt` establish rarity weights, reward-driven bounty rarity, and amount/worth behavior. These runtime-relative paths resolve under `/mnt/c/Users/alexh/curseforge/minecraft/Instances/Superior`.
- [Extra Bounties maintainer guide](https://github.com/DevDyna/DataThings/tree/Extra-Bounties): generated compatibility data and the distinction between objective, reward, and shared pools. The exact installed artifacts above own the version-specific findings.
