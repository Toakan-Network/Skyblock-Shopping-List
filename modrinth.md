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
- [Saved lists](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Menu#list)
- [Per-profile data](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Getting-Started#per-profile-data)
- [Craftable](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#craftable)
- [Forging](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#forging)
- [Recipe locks](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#recipe-locks)
- [NPC price](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#npc-price)
- [Forge times](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#forge-times)
- [Recipes beyond the crafting table](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Recipes-and-Items#recipes)
- [Coins count as an item](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Recipes-and-Items#coins)
- [Pop-ups](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Shopping-List-HUD#pop-ups)
- [Fully customisable look](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Appearance)
- [Presets and share codes](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Appearance)
- [Menu tabs](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Menu)
- [Works with SkyBlock Item List and Mod Menu (optional)](https://github.com/Toakan/Skyblock-Shopping-List/wiki/Other-Mods)

How each one works is in the [wiki](https://github.com/Toakan/Skyblock-Shopping-List/wiki).

| Plan big goals | Every step, in game | Know what to unlock |
| --- | --- | --- |
| ![Golden Dragon (Legendary) broken down into coins, gold blocks and Perfect gems](https://raw.githubusercontent.com/wiki/Toakan/Skyblock-Shopping-List/Images/Golden-Dragon-Recipe.png) | ![The HUD with the full Chestplate of Divan tree](https://raw.githubusercontent.com/wiki/Toakan/Skyblock-Shopping-List/Images/Recipe-Tree-HUD.png) | ![Recipe lock tooltip listing HotM and collection requirements](https://raw.githubusercontent.com/wiki/Toakan/Skyblock-Shopping-List/Images/Recipe-Locks-Tooltip.png) |

**Coming from Inventory Reader?** This mod grew out of [Inventory Reader](https://github.com/Scholiboi/InventoryReader) by Scholiboi. Remove the old jar before installing; your data is moved to `config/skyblock-shopping-list/` automatically on first launch.

## Getting started
The mod only knows what you have shown it. Open your backpacks and ender chest pages once on SkyBlock, plus each Accessory Bag page, each wardrobe page and the Stats & Equipment menu (for worn equipment). Sacks only need opening when your list needs them: a chat line names which ones. After that, counts stay up to date from your inventory, the containers you open and the `[Sacks]` chat summaries (keep those enabled in your SkyBlock settings).

## Keys and commands
| Key / command | Action |
| --- | --- |
| `V` or `/ssl menu` | Open the menu |
| `H` | Toggle the HUD |
| `B` or `/ssl hud` | Move / resize the HUD |
| `/ssl` | List commands |
| `/ssl reset` | Delete the current profile's tracked data and the HUD settings (saved lists and presets are kept) |

Keys can be rebound under Options > Controls > Key Binds > Skyblock Shopping List.

## Hypixel rules
Designed to stay within the [Hypixel Allowed Modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications) guidelines:
- Read-only: it looks at screens you open and chat messages you receive, and never sends packets, chat messages or commands.
- No automation: no clicking, crafting, moving items or opening menus for you.
- Only active on Hypixel SkyBlock. In lobbies, other games and other servers the HUD is hidden and nothing is tracked.

As Hypixel states, every modification is used at your own risk.

## Network access
Recipe Data: On startup the mod downloads the [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) archive from GitHub to get recipes (cached with an ETag, so unchanged data isn't downloaded again). Nothing from the archive is executed.

NPC Sell Prices: On startup it reads Hypixel's public item list for NPC sell prices, and only downloads it again when Hypixel has changed it.

Mayor Interaction Data: At most once an hour while you're on SkyBlock it reads Hypixel's public election data to know whether Cole's Molten Forge perk is active. With Forge times turned off (Settings > Rows), this data is not read.

No player data is sent anywhere.

Security scan: the 4.28.1 jar was scanned with [Java-Triage](https://github.com/cev-api/Java-Triage), with no malicious behavior found. [Report and jar hashes](https://github.com/Toakan/Skyblock-Shopping-List/tree/master/security/java-triage).

## Requirements
Minecraft 26.2 or 26.3 (one jar for both), Java 25, Fabric Loader 0.19.5+, Fabric API, YetAnotherConfigLib (already installed if you use Skyblocker) and the [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) (required: the game won't start without it).

## Known limitations
- Sack contents only fully resync when you open a sack; in between they follow the `[Sacks]` chat summaries. A reminder appears if no sack has been opened for an hour.
- Pets that only drop have no recipe.

## License and credits
GPL-3.0-only, with extra terms: keep the credits, mark modified versions, and give them their own name. Originally based on [Inventory Reader](https://github.com/Scholiboi/InventoryReader) by Scholiboi, whose inventory reading, recipe trees and HUD this mod was built on. Recipe and item data comes from [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO). Source code: [Toakan/Skyblock-Shopping-List](https://github.com/Toakan/Skyblock-Shopping-List).
