# Skyblock Shopping List

[![build](https://github.com/Toakan/Skyblock-Shopping-List/actions/workflows/build.yml/badge.svg)](https://github.com/Toakan/Skyblock-Shopping-List/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/Toakan/Skyblock-Shopping-List?label=release)](https://github.com/Toakan/Skyblock-Shopping-List/releases/latest)
[![license](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-62b47a)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/loader-Fabric-dbd0b4)](https://fabricmc.net/)

> **Built for Ironman first, works for everyone.**

Skyblock Shopping List is a Fabric client mod for Hypixel SkyBlock. It keeps a count of the items in your inventory, sacks, backpacks and ender chest, and shows a shopping list HUD for the recipes you pick: what you can already craft and what you still need. Lists can be saved under a name and swapped back in later.

![The List tab: pick recipes, see the full tree with forge times, NPC price and saved lists](https://raw.githubusercontent.com/wiki/Toakan/Skyblock-Shopping-List/Images/Live-Recipe-Track.gif)

### Ironman first
This mod is designed around Ironman play. With no Bazaar or Auction House, every item has to be gathered or crafted yourself, so knowing exactly what a recipe tree still needs, which sacks hold it, what is cooking in the Forge and what an NPC sells or pays is the whole game. New features are planned with that in mind: nothing relies on Bazaar or Auction House prices, and prices shown are NPC coin prices.

### Works for everyone
Nothing in the mod is limited to Ironman profiles. On a regular profile it works the same way: track your stock across inventory, sacks and storage, plan big crafts down to raw materials, and see what you can make right now.

## Features
- [Shopping list HUD](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD)
- [Easy start: no need to open every sack](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Getting-Started#first-things-to-open)
- [Recipe filters](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Menu#list)
- [Saved lists and share codes](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Menu#list)
- [Per-profile data](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Getting-Started#per-profile-data)
- [Craftable](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#craftable)
- [Forging](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#forging)
- [Recipe locks](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#recipe-locks)
- [NPC price](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#npc-price)
- [Forge times](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#forge-times)
- [Recipes beyond the crafting table](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Recipes-and-Items#recipes)
- [Coins count as an item](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Recipes-and-Items#coins)
- [Powder goals](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Recipes-and-Items#powder)
- [Notifications: chat, toast or both](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#notifications)
- [Fully customisable look](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Appearance)
- [Presets and share codes](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Appearance)
- [Menu tabs](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Menu)
- [Works with SkyBlock Item List and Mod Menu (optional)](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Other-Mods)

How each one works is in the [wiki](https://github.com/Toakan/Skyblock-Shopping-List/wiki).

| Plan big goals | Every step, in game | Know what to unlock |
| --- | --- | --- |
| ![Golden Dragon (Legendary) broken down into coins, gold blocks and Perfect gems](https://raw.githubusercontent.com/wiki/Toakan/Skyblock-Shopping-List/Images/Golden-Dragon-Recipe.png) | ![The HUD with the full Chestplate of Divan tree](https://raw.githubusercontent.com/wiki/Toakan/Skyblock-Shopping-List/Images/Recipe-Tree-HUD.png) | ![Recipe lock tooltip listing HotM and collection requirements](https://raw.githubusercontent.com/wiki/Toakan/Skyblock-Shopping-List/Images/Recipe-Locks-Tooltip.png) |

## Coming from Inventory Reader?
Skyblock Shopping List grew out of [Inventory Reader](https://github.com/Scholiboi/InventoryReader) by Scholiboi (see [Credits](#license-and-attribution)). Remove the old Inventory Reader jar from `mods/` before installing; its data folder (`.ir-data`, or `.skyblock-shopping-list` from earlier versions of this mod) is moved to `config/skyblock-shopping-list/` automatically on first launch.

## Requirements
- Minecraft 26.2 or 26.3 (the same jar runs on both)
- Fabric Loader 0.19.5 or newer
- Fabric API 0.161.0 or newer, built for your Minecraft version
- YetAnotherConfigLib 3.9.7 or newer, built for your Minecraft version (already installed if you use Skyblocker)
- [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) 1.0.2 or newer (official; used to know when you are on SkyBlock)
- Java 25
- Optional: [SkyBlock Item List](https://modrinth.com/mod/skyblock-item-list) and [Mod Menu](https://modrinth.com/mod/modmenu) (see [Other Mods](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Other-Mods))

## Installation
1. Install Fabric Loader for Minecraft 26.2 or 26.3.
2. Put Fabric API, YetAnotherConfigLib, the Hypixel Mod API and the Skyblock Shopping List jar in your `mods/` folder.
3. Launch Minecraft with the Fabric profile.

## First-time setup
The mod only knows what you have shown it. Open your backpacks and ender chest pages once in SkyBlock, plus each Accessory Bag page, each wardrobe page and the Stats & Equipment menu (for worn necklace, cloak, belt and gloves). Worn armor counts with your inventory, and what is fitted in an item and comes back out on removal (drill engine, fuel tank and upgrade module, socketed gemstones) counts wherever the item is. After that, counts stay up to date from your inventory, from the containers you open, and from the `[Sacks]` chat summaries.

You don't need to open every sack. When you join SkyBlock and each time you add a recipe, one chat line names the sacks your list's materials live in (e.g. "Open your Enchanted Mining Sack for accurate counts."), and until you open them:
- rows for those materials are grey with `≥` (have at least, e.g. `≥12/160`) or `≤` (still need at most) instead of a number that may be too low,
- the HUD's **Total** row shows how far you've got, e.g. `(sacks 1/3 read)`,
- in the sack menus (`/sacks`), the sacks still to open have a gold square behind them.
Everything goes back to normal as soon as each sack has been opened once.

## Keys and commands
| Key / command | Action |
| --- | --- |
| `V` or `/ssl menu` | Open the menu |
| `H` | Toggle the HUD |
| `B` or `/ssl hud` | Move / resize the HUD |
| `/ssl` | List commands |
| `/ssl reset` | Delete the current profile's tracked data and the HUD settings |
| `/ssl credits` | Show credits |

Keys can be rebound under Options > Controls > Key Binds > Skyblock Shopping List (or Settings → Key binds...).

## SkyBlock only
The mod only runs on Hypixel SkyBlock. It uses the official Hypixel Mod API's location event (Hypixel's own signal), so the Hypixel Mod API is required; the game won't start without it. In lobbies, other Hypixel games and other servers the HUD is hidden, the keys do nothing and nothing is tracked, so other inventories never change your counts. The `/ssl` commands still work everywhere.

## Hypixel rules
Skyblock Shopping List is designed to stay within the [Hypixel Allowed Modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications) guidelines:
- It is read-only. It looks at screens you open and chat messages you receive (only Hypixel's own `[Sacks]` summaries and `Profile ID` line, matched exactly so player chat can't trigger them), and never sends packets, chat messages or commands to the server. It never hides or changes a message.
- It does not automate anything: no clicking, crafting, moving items or opening menus for you.
- The HUD only shows your own items and recipe arithmetic.

Outside the game connection it downloads recipes from the NEU repository and NPC sell prices from Hypixel's public, keyless item list (`api.hypixel.net/v2/resources/skyblock/items`), and at most once an hour while you are on SkyBlock reads Hypixel's public, keyless election data (`api.hypixel.net/v2/resources/skyblock/election`) to know whether Cole's Molten Forge perk is active.

As Hypixel states, every modification is used at your own risk.

## Where data is stored
Everything lives in `config/skyblock-shopping-list/` inside your Minecraft folder.

Each SkyBlock profile keeps its own data in `profiles/<account>/<profile>/`, so switching profile (or account) switches the counts and the shopping list with it. The profile is taken from Hypixel's `Profile ID: ...` chat line, sent each time you join SkyBlock. Right after a server change, tracking waits for that line (it is read even when another mod hides it), so items are never booked to the wrong profile. Data from before profiles existed is moved into the first profile seen. Per profile:
- `resources.json`: tracked item counts
- `shopping_list.json`: the shopping list
- `inventorydata.json`, `allcontainerData.json`, `sacks.json`, `sacks_meta.json`: last-seen inventory, container and sack contents, used to work out changes
- `coins.json`: last bank balance seen
- `forge.json`: what was cooking in the Forge when you last opened it
- `forge_speed.json`: your Quick Forge % and the current mayor forge bonus

`profiles/<account>/last_profile.txt` remembers the profile you used last. Shared by all profiles:
- `hud_style.json`: your Appearance settings
- `saved_lists.json`: your saved shopping lists; kept by a data reset
- `presets/`: your saved HUD presets, one file each (share them by sending the file or a share code); kept by a data reset
- `widget_config.json`: HUD position and size, expanded nodes and settings
- `forging.json`, `gemstone_recipes.json`: built-in fallback recipes
- `recipes_remote.json`, `recipes_remote_forge.json`, `recipes_remote_shop.json`: crafting, forge and NPC shop recipes parsed from the NEU repository
- `item_names.json`: SkyBlock item ID to name table
- `item_requirements.json`: what unlocks each recipe (collection, HotM or slayer level), from the NEU repository
- `npc_buy_prices.json`, `npc_sell_prices.json`: coins per item to buy from NPC shops (NEU repository) and that NPCs pay (Hypixel's item list)
- `forge_times.json`: base forge time of each forge item, from the NEU repository
- `item_lore.json`: item descriptions shown when you hover a recipe in the menu, from the NEU repository
- `remote_sources.json`, `remote_sources_meta.json`: recipe source list and download cache state
- `neu-repo-extracted/`: the unpacked NEU repository, only while it is being parsed (deleted afterwards)

## Network access
On startup the mod downloads the [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) archive from GitHub (`codeload.github.com`) to get recipes. The request is a plain GET with an ETag, so unchanged data is not downloaded again. It then reads NPC sell prices from Hypixel's public, keyless item list (`api.hypixel.net/v2/resources/skyblock/items`, about 2 MB compressed); it asks with the date of the copy it has, so the list is only downloaded again when Hypixel changed it, and only the prices are kept. No player data is sent anywhere.

The sources are listed in `remote_sources.json`. Only `https` URLs and local files are accepted. The archive is unpacked to `neu-repo-extracted/` with path-traversal, entry-count and size limits, then parsed as JSON and deleted again. Nothing from it is executed.

## Security scan
The 4.30.7 release jar, and the neurepoparser library bundled in it, were scanned with [Java-Triage](https://github.com/cev-api/Java-Triage), a static scanner for malicious Java code and Minecraft mods (network off, nothing
uploaded). Result: no confirmed malicious behavior and no known malware matched. The report, the jar hashes to check
your copy against and an explanation of each flagged item are in [security/java-triage](security/java-triage/README.md).

## Building from source
Requires JDK 25.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runClient` starts a development client.

## Known limitations
- Items are matched by their SkyBlock ID where the item has one, so reforges and stars don't matter. Items without an ID (some sack and menu icons) fall back to their name, with colour codes and stat symbols ignored.
- Sack contents only resync when you open a sack; between opens they follow the `[Sacks]` chat summaries, so keep those enabled in your SkyBlock settings. If no sack has been opened for an hour while the list has items, a message reminds you (Settings > Notifications > Sack reminder).
- Recipes cover what the NEU repository lists as crafting, forge, NPC shop and Kat upgrade recipes. Pets that only drop have no recipe.

## License and attribution
- Code: GPL-3.0-only (see `LICENSE`) with additional terms under GPLv3 section 7 (see `NOTICE`): keep the credits to both authors, mark modified versions as changed, and give a modified version its own name, mod id and icon.
- Originally based on [Inventory Reader](https://github.com/Scholiboi/InventoryReader) by Scholiboi. Its core ideas and code (reading items from your inventory, sacks and storage, working out recipe trees, the HUD and the recipe viewer) are what this mod was built on. Thanks to Scholiboi for the original project. Inventory Reader is licensed CC-BY-SA-4.0; its modified code is used here under GPLv3, which Creative Commons lists as compatible with CC-BY-SA-4.0.
- Versions up to 4.26.1 were released under CC-BY-SA-4.0.
- Recipe and item data comes from NotEnoughUpdates-REPO; follow its license when reusing that data.
