# Extra Bounties Rarity Classification Report

Status: active
Doc Type: report
Owner: `addons/superior_story`
Authoritative For: per-mod tier counts, the EPIC and LEGENDARY entry list, provisional assignments, and the decree coverage audit of the shipped Extra Bounties rarity patch data
Supersedes: none
Superseded By: none
Last Updated: 2026-09-30 America/Denver

The shipped patch files under `src/main/resources/data/superiorstory/bounty_pools/extrabounties/` are the source of truth for every individual assignment. This report records what was classified, the method, and what remains provisional. Rubric: [EXTRA_BOUNTIES_RARITY_RULESET.md](../reference/EXTRA_BOUNTIES_RARITY_RULESET.md). Plan rows: R10 and R11 in the [NPC bounty plan](../plans/SUPERIOR_STORY_BOUNTIFUL_NPC_BOUNTIES_PLAN_2026-09-29.md).

## Scope

Extra Bounties 2.1 has 3,256 entries in 140 pool files. Patch data ships only for pools whose `requires` mods are present in this instance's `mods/` folder, plus Gateways to Eternity and Ice and Fire, which the operator wants classified for future use. A patch for an absent mod would be skipped by Bountiful (`requires` is copied), so none were authored for those.

- Installed and classified: Alex's Caves, Alex's Mobs, Artifacts, Create, Farmer's Delight, Iron's Furnaces, Iron's Spells 'n Spellbooks, Mowzie's Mobs, Quark, Sophisticated Backpacks, Sophisticated Storage, and the 11 pools without a `requires` (`decree`, `discs`, `enchants`, `explorer`, `mob_drop`, `pottery`, `rarities`, `resources`, `sculk`, `treasures`, `trims`).
- Classified for future use (not installed): Gateways to Eternity (`gateways_rew`, 225 pearls) and Ice and Fire (`iceandfire_any`, `iceandfire_obj`, `iceandfire_rew`). `immersive_gateways` in `mods/` is a different mod and does not satisfy the `gateways` requirement.
- Not classified (mod absent, patch would be inert): 94 pool files, 1,953 entries, for Aquaculture, Ars Nouveau and Ars Additions, Atmospheric, Autumnity, Berry Good, Biomancy, Building Gadgets 2, Buzzier Bees, Caverns and Chasms, Dice, DimDoors, Embers, Endergetic, Environmental, Everlasting Abilities, Experience Obelisk, GameDiscs, Goety, Inventory Pets, Iron Chests, LaserIO, Malum, Metal Barrels, Modular Routers, Nameless Trinkets, Neapolitan, Powah, PPFluids, Pretty Pipes, Reliquary, Roots Classic, Savage and Ravage, Tinkers' Construct, Tombstone, Unusual Prehistory, Upgrade Aquatic, Void Miners, Warp Pipes, and Waystones. Classify them when those mods are added.

Result: 46 patch files, 1,303 entries.

## Method

Ruleset order applied: stock anchors first (exact item and role reused, for example enchanted books, banner patterns, recovery compass, the `rarities` pool items, diamond and iron objectives COMMON, dried kelp block objective RARE), then functional equivalents, then normal survival acquisition, then variant NBT. Acquisition evidence for installed mods came from their own loot tables and recipes in the deployed jars (entity, chest, and block loot; crafting ingredients such as electron tubes, precision mechanisms, and arcane ingots). Each entry carries one static rarity; quantity was not used as a formula and `unitWorth` was ignored.

Variant handling: level-I enchanted books follow the stock librarian, cleric, and fisherman classifications (mending LEGENDARY, frost walker, lure, and flame UNCOMMON, protection, sharpness, and thorns COMMON). Quark ancient tomes are EPIC for the enchantments that matter most at max level (fortune, unbreaking, efficiency, sharpness, power, looting) and RARE otherwise. Alex's Caves tablets and codices get one tier per item because the biome does not change acquisition. Gateway pearls are classified per mob and size.

Gateway pearls: mob score (harmless 0, common hostile 1, dangerous 2, miniboss 3) sets the row and size (small 25 mobs and 10 loot rolls, medium 50 and 20, large 75 and 30) sets the column. Score 0: COMMON, COMMON, UNCOMMON. Score 1: COMMON, UNCOMMON, UNCOMMON. Score 2: RARE, RARE, EPIC. Score 3 (warden): EPIC, LEGENDARY, LEGENDARY. Overrides: evoker (guaranteed totem drop, 10 to 30 totems) EPIC, LEGENDARY, LEGENDARY; elder guardian EPIC, EPIC, LEGENDARY; vex UNCOMMON, RARE, RARE (its only loot is mansion chest rolls). Gateway definitions were read from the Extra Bounties jar (5 waves each, entity loot plus chest loot tables for several mobs). Gateways to Eternity itself is not installed, so its runtime behavior was not verified.

## Tier counts by mod

| Mod | COMMON | UNCOMMON | RARE | EPIC | LEGENDARY | Total |
|---|---:|---:|---:|---:|---:|---:|
| alexscaves | 1 | 45 | 33 | 7 | 0 | 86 |
| alexsmobs | 11 | 67 | 27 | 4 | 0 | 109 |
| artifacts | 3 | 20 | 19 | 2 | 0 | 44 |
| create | 74 | 64 | 33 | 0 | 0 | 171 |
| farmersdelight | 46 | 42 | 6 | 0 | 0 | 94 |
| gateways | 104 | 89 | 16 | 11 | 5 | 225 |
| iceandfire | 0 | 26 | 82 | 27 | 3 | 138 |
| ironfurnaces | 24 | 16 | 7 | 2 | 1 | 50 |
| irons_spellbooks | 1 | 31 | 25 | 15 | 0 | 72 |
| mowziesmobs | 3 | 13 | 9 | 4 | 1 | 30 |
| quark | 1 | 47 | 19 | 6 | 1 | 74 |
| sophisticatedbackpacks | 1 | 17 | 6 | 1 | 0 | 25 |
| sophisticatedstorage | 3 | 19 | 3 | 1 | 0 | 26 |
| vanilla | 49 | 65 | 29 | 11 | 5 | 159 |
| **Total** | **321** | **561** | **314** | **91** | **16** | **1303** |

Shares: COMMON 25%, UNCOMMON 43%, RARE 24%, EPIC 7%, LEGENDARY 1%. Stock reward-entry shares are 34, 23, 19, 15, and 9 percent; the guide treats that spread as a sanity check, not a quota. This set skews UNCOMMON because most modded entries are specialized components or modest equipment. Ice and Fire skews RARE and above because nearly every entry is a dragon, sea serpent, or dangerous-mob part.

Gateway pearl split: COMMON 104, UNCOMMON 89, RARE 16, EPIC 11, LEGENDARY 5.

## Decree coverage (R11)

Every Extra Bounties decree loaded in this instance lists the shared `rarities` pool as a reward pool, and `rarities` contains `rabbit_stew` as COMMON (stock anchor). Audit of the 18 loaded Extra decrees (alexscaves, alexsmobs, artifacts, create_craft, create_fluid, create_gen, create_logistic, create_motion, create_other, farmersdelight, gateways, iceandfire, ironfurnaces, irons_spellbooks, mowziesmobs, quark, sophisticatedbackpacks, sophisticatedstorage) counted COMMON reward entries after patching: every decree has at least one (only the rabbit stew for create_craft and sophisticatedbackpacks; several more for the rest). The gateways and iceandfire decrees load only once those mods are installed. The 12 stock Bountiful decrees are untouched and keep their stock COMMON rewards. No premium reward was downgraded to fill a COMMON slot.

## EPIC and LEGENDARY entries

LEGENDARY:

- vanilla/enchants: enchanted_book [mending L1]
- vanilla/explorer: mojang_banner_pattern
- vanilla/explorer: recovery_compass
- gateways/gateways_rew: gate_pearl [large/elder_guardian]
- gateways/gateways_rew: gate_pearl [medium/evoker]
- gateways/gateways_rew: gate_pearl [large/evoker]
- gateways/gateways_rew: gate_pearl [medium/warden]
- gateways/gateways_rew: gate_pearl [large/warden]
- iceandfire/iceandfire_rew: dragonsteel_fire_ingot
- iceandfire/iceandfire_rew: dragonsteel_lightning_ingot
- iceandfire/iceandfire_rew: dragonsteel_ice_ingot
- ironfurnaces/ironfurnaces_sell: upgrade_netherite
- mowziesmobs/mowziesmobs_rew: sol_visage
- quark/quark_rew: dragon_scale
- vanilla/rarities: heart_of_the_sea
- vanilla/rarities: totem_of_undying

EPIC:

- alexscaves/alexscaves_out: pure_darkness
- alexscaves/alexscaves_out: quarry
- alexscaves/alexscaves_out: nuclear_bomb
- alexscaves/alexscaves_out: totem_of_possession
- alexscaves/alexscaves_out: quarry_smasher
- alexscaves/alexscaves_out: raygun
- alexscaves/alexscaves_out: submarine
- alexsmobs/alexsmobs_rew: transmutation_table
- alexsmobs/alexsmobs_rew: mysterious_worm
- alexsmobs/alexsmobs_rew: tarantula_hawk_elytra
- alexsmobs/alexsmobs_rew: hemolymph_blaster
- artifacts/artifacts: chorus_totem
- artifacts/artifacts: crystal_heart
- vanilla/explorer: skull_banner_pattern
- vanilla/explorer: creeper_banner_pattern
- gateways/gateways_rew: gate_pearl [large/blaze]
- gateways/gateways_rew: gate_pearl [small/elder_guardian]
- gateways/gateways_rew: gate_pearl [medium/elder_guardian]
- gateways/gateways_rew: gate_pearl [large/enderman]
- gateways/gateways_rew: gate_pearl [small/evoker]
- gateways/gateways_rew: gate_pearl [large/ghast]
- gateways/gateways_rew: gate_pearl [large/piglin_brute]
- gateways/gateways_rew: gate_pearl [large/ravager]
- gateways/gateways_rew: gate_pearl [large/shulker]
- gateways/gateways_rew: gate_pearl [small/warden]
- gateways/gateways_rew: gate_pearl [large/wither_skeleton]
- iceandfire/iceandfire_any: fire_dragon_heart
- iceandfire/iceandfire_any: dragon_skull_fire
- iceandfire/iceandfire_any: dragon_skull_ice
- iceandfire/iceandfire_any: lightning_dragon_heart
- iceandfire/iceandfire_any: hydra_heart
- iceandfire/iceandfire_any: dragon_skull_lightning
- iceandfire/iceandfire_any: ice_dragon_heart
- iceandfire/iceandfire_rew: dragonforge_lightning_input
- iceandfire/iceandfire_rew: dragonegg_amythest
- iceandfire/iceandfire_rew: dragonforge_ice_core_disabled
- iceandfire/iceandfire_rew: dragonforge_fire_input
- iceandfire/iceandfire_rew: dragonegg_copper
- iceandfire/iceandfire_rew: dragonegg_white
- iceandfire/iceandfire_rew: dragonforge_ice_input
- iceandfire/iceandfire_rew: dragonegg_blue
- iceandfire/iceandfire_rew: dread_key
- iceandfire/iceandfire_rew: dragonegg_bronze
- iceandfire/iceandfire_rew: dragonegg_silver
- iceandfire/iceandfire_rew: dragonegg_black
- iceandfire/iceandfire_rew: dragonegg_sapphire
- iceandfire/iceandfire_rew: dragonegg_red
- iceandfire/iceandfire_rew: dragonforge_lightning_core_disabled
- iceandfire/iceandfire_rew: dragonegg_gray
- iceandfire/iceandfire_rew: dragonforge_fire_core_disabled
- iceandfire/iceandfire_rew: gorgon_head
- iceandfire/iceandfire_rew: dragonegg_electric
- iceandfire/iceandfire_rew: dragonegg_green
- ironfurnaces/ironfurnaces_sell: rainbow_core
- ironfurnaces/ironfurnaces_sell: rainbow_plating
- irons_spellbooks/irons_spellbooks_any: dragonskin
- irons_spellbooks/irons_spellbooks_any: legendary_ink
- irons_spellbooks/irons_spellbooks_rew: eldritch_manuscript
- irons_spellbooks/irons_spellbooks_rew: fire_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: ice_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: lightning_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: holy_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: ender_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: blood_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: evocation_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: nature_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: mana_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: cooldown_upgrade_orb
- irons_spellbooks/irons_spellbooks_rew: protection_upgrade_orb
- vanilla/mob_drop: glow_ink_sac
- mowziesmobs/mowziesmobs_any: ice_crystal
- mowziesmobs/mowziesmobs_any: wrought_helmet
- mowziesmobs/mowziesmobs_rew: earthrend_gauntlet
- mowziesmobs/mowziesmobs_rew: wrought_axe
- quark/quark_rew: ancient_tome [power L5]
- quark/quark_rew: ancient_tome [unbreaking L3]
- quark/quark_rew: ancient_tome [fortune L3]
- quark/quark_rew: ancient_tome [efficiency L5]
- quark/quark_rew: ancient_tome [looting L3]
- quark/quark_rew: ancient_tome [sharpness L5]
- vanilla/rarities: nautilus_shell
- vanilla/rarities: prismarine_crystals
- sophisticatedbackpacks/sophisticatedbackpacks_upgrade: everlasting_upgrade
- sophisticatedstorage/sophisticatedstorage_upgrade: diamond_to_netherite_tier_upgrade
- vanilla/trims: silence_armor_trim_smithing_template
- vanilla/trims: spire_armor_trim_smithing_template
- vanilla/trims: eye_armor_trim_smithing_template
- vanilla/trims: vex_armor_trim_smithing_template
- vanilla/trims: ward_armor_trim_smithing_template
- vanilla/trims: netherite_upgrade_smithing_template

## Source checks and remaining provisional assignments

A second pass read the mods' class files (javap on the deployed jars) and their data for the entries the JSON loot and recipe scan could not source. Results:

- Alex's Mobs, confirmed in code: `lost_tentacle` (giant squid death drop), `ambergris` and `cachalot_whale_tooth` (cachalot whale), `shed_snake_skin` (anaconda shedding), `komodo_spit` (komodo dragon), `spiked_scute` (alligator snapping turtle), `mungal_spores` (mungus), `potted_flutter` (flutter plus flower pot), `stink_bottle` (bottle on skunk spray), `terrapin_egg` (terrapin egg block), `gongylidia` (leafcutter ant chamber), `blobfish_bucket` (bucket on blobfish), `shrimp_fried_rice` (mantis shrimp cooks rice), `ghostly_pickaxe` (underminer equipment). `pigshoes` is a global loot modifier on piglin bartering and `ancient_dart` one on jungle temple chests. `music_disc_daze` is a Capsid conversion of any music disc. `mysterious_worm` is a Capsid conversion of mosquito larvae and summons the Void Worm boss, so it moved from RARE to EPIC. The earlier tiers for the others stand.
- Alex's Caves: `cave_codex` is crafted at the Spelunkery Table from a book and a cave tablet (tablets come from ruin and structure chests), so UNCOMMON stands. `submarine` has no recipe; the item comes from the submarine in Abyssal Chasm ruins after repair, so EPIC stands.
- Mowzie's Mobs: `grant_suns_blessing` is Umvuthi's trade output (RARE stands); `captured_grottol` comes from killing a grottol with silk touch (UNCOMMON stands).
- Iron's Furnaces: the first scan dropped tag ingredients. Real recipes: `item_copy`, `item_spooky`, `item_xmas` are paper, pumpkin, or dye plus a furnace (now COMMON); `upgrade_gold2` is 6 gold ingots, a silver ingot, and a gold block (RARE); `upgrade_netherite` is 4 netherite ingots (LEGENDARY); `rainbow_core` needs two netherite furnaces and `rainbow_plating` needs seven or eight furnaces including crystal, emerald, and obsidian (both EPIC). Other upgrade tiers were unchanged by the recipes.
- Still unverified: Ice and Fire and Gateways to Eternity (mods not installed, so knowledge-based; check against their loot tables and recipes when added, especially dragon parts, dragon forge blocks, dragon eggs, and dragonsteel), and Artifacts (assigned by ability strength; acquisition is uniform structure loot). Iron's Spells `lightning_bottle` was not rechecked.

## Giver assignment

Investigation (class files and registries of the installed jars): no installed mod registers a villager profession (Alex's Caves and Alex's Mobs register point-of-interest types for mob AI only). Friendly or trading NPCs exist in three mods, so those mods' decrees go to their own NPCs as Story scenes that name the Extra Bounties decree directly:

| Mod | Giver | Notes |
|---|---|---|
| Alex's Caves | `alexscaves:deep_one` | Barters and keeps per-player reputation; neutral rather than fully friendly, so in-game behavior when talked to is unverified |
| Mowzie's Mobs | `mowziesmobs:sculptor` (Tongbi the Sculptor) | Changed from Umvuthi on operator instruction; the Sculptor is a boss-type mob, so in-game behavior when talked to is unverified |
| Iron's Spellbooks | `irons_spellbooks:priest`, `apothecarist`, `pyromancer`, `cryomancer` | Priest and Apothecarist are merchants; Pyromancer and Cryomancer added on operator instruction (treated as passive); all offer the `irons_spellbooks` decree |

Mods with neither NPCs nor professions go to the vanilla villager profession whose name best fits the items (names only, no gameplay reasoning). The mod's pools are merged into that profession's decree as data, gated by the mod's `requires`, so the profession's stock bounties stay and the mod's items are added. The shared pools (`treasures`, `resources`, `rarities`, `decree`, `pottery`, `trims`, `explorer`, `enchants`) are not merged into professions.

| Mod | Profession | Name basis |
|---|---|---|
| Alex's Mobs | butcher | animal parts (fur, feathers, eggs, fish, horns) |
| Artifacts | cleric | relics, pendants, necklaces, totems |
| Create | toolsmith | machines, gears, shafts, casings |
| Farmer's Delight | farmer | crops and meals |
| Iron's Furnaces | armorer | iron, furnaces, plating |
| Quark | librarian | tomes, runes, discs |
| Sophisticated Backpacks | leatherworker | backpacks |
| Sophisticated Storage | mason | storage blocks, tier upgrades |
| Gateways to Eternity (not installed) | cartographer | gateways, pearls |
| Ice and Fire (not installed) | weaponsmith | dragonsteel, dragonbone, dragon forge |

Files: `bounty_decrees/mods/<mod>/<profession>.json` (merged by base file name), `bounty_decrees/superiorstory/mason.json` and `weaponsmith.json` (new professions have no stock decree, so they use Bountiful's `_all_objs` and `_all_rews`), and `scenes/bounty_<npc>.json`. Ice and Fire and Gateways to Eternity were assumed to add no friendly NPCs or professions (knowledge-based, jars not installed); revisit when they are added.

## Data check

One-off script run at authoring time: 46 patch files; every patch has the same `requires` as its base pool; every patch key exists in the Extra Bounties pool of the same base name, in the same order; only `rarity` is set; every rarity is a valid Bountiful enum name; no `replace`; no pool-level `weightMult` (no Extra Bounties pool defines one and no base name collides with a stock Bountiful pool); 1,303 patch entries match the 1,303 in-scope entries of the 3,256 total.
