# Skyblock Shopping List (formerly InventoryReader)

Fabric client mod (mod id `skyblock-shopping-list`, Java package `inventoryreader.ir`) for Hypixel SkyBlock, Minecraft 26.2 and 26.3 from one jar (Loom 1.17, Java 25). Build with `./gradlew build`.

## Versioning
`mod_version` in `gradle.properties` is MAJOR.MINOR.PATCH, bumped in the same commit as the change:
- Major feature (a new area such as Appearance settings, the Forging panel, pet recipes): bump MINOR,
  reset PATCH (4.4.0 -> 4.5.0).
- Anything that adds to, removes from or modifies an existing feature (e.g. a new Appearance option such
  as rounded corners, per-section text sizes, list reordering), and every bug fix: bump PATCH
  (4.4.0 -> 4.4.1).
- MAJOR only for breaking changes such as a Minecraft version port.
One bump per change. Docs-only commits (readme, this file) don't need a bump.
Order per change: edit, bump `mod_version`, `./gradlew build`, commit. Bump before the final build so
`build/libs` has the jar for the committed version.

## Code
- Reuse what exists before writing new code: helpers such as `JsonFiles`, `ItemNames`, `RecipeManager`
  and `ResourcesManager` lookups, and the fetcher's `writeSnapshot`. Match the surrounding patterns.
- Minimal dependencies: no new library or mod integration unless the benefit is large. Prefer the
  smallest change that does the job. A library that takes over code we would otherwise maintain (SkyBlock
  parsing that breaks when Hypixel changes a menu or message) counts as a large benefit.
- SkyblockAPI (bundled, Jar-in-Jar): prefer its data over writing new SkyBlock parsing. Call it from Java via
  `X.INSTANCE`; avoid members typed `SkyBlockId` (name-mangled, not callable from Java). Its extra traffic
  (party request, price polling) is blocked by `SkyblockApiGuard` and the `SkyblockApi*Mixin`s while no other
  mod uses it; keep that list current when bumping its version.
- Keep the mod resource light (disk, network, CPU): no large temp data, extra polling or extra downloads.
  Weigh the footprint when proposing a feature.

## Docs
When a change adds or changes something a player would notice, update `readme.md` (detailed) and, if it is
worth advertising, `modrinth.md` (short, synced to the Modrinth description) in the same commit. Internal
changes and small fixes don't need a docs note.

## Hypixel rules
The mod must stay read-only and client-side (see the "Hypixel rules" section in `readme.md`): never send
packets, chat or commands, never cancel or modify events, never automate clicks or item movement.
Tracking, the HUD and keybinds run only on SkyBlock (`SkyblockDetector`).

## Minecraft versions
One jar covers 26.2 and 26.3 (`"minecraft": ["~26.2", "~26.3"]`). It is built against 26.2, the lower bound.
- No `org.lwjgl.glfw`: 26.3 moved to SDL, so GLFW is missing there.
- SkyblockAPI has one build per Minecraft version, each hard-requiring it; both the 26.2 and 26.3 builds are
  nested in the jar (fetched as plain files, see `build.gradle`) and Fabric loads the matching one.
  We compile against the 26.2 build: only use SkyblockAPI classes that exist in both. The 26.3 build drops
  its deprecated `WardrobeAPI` (use `ArmorWardrobeAPI` / `EquipmentWardrobeAPI`) and `RepoStatusEvent` (use `RepoEvent`).
- Don't reference `InputConstants` key or mouse codes directly. Their values differ between versions and
  javac inlines them, so read them with `InventoryReaderClient.inputCode("KEY_V")`.
- Before a release, compile against 26.3 too (swap `minecraft_version`, `fabric_version` and `yacl_version`)
  and test in a 26.3 client.
