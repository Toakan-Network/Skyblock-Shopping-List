# Security scan: Skyblock Shopping List

Static malware scan of the release jar and the library it bundles, run on 2026-10-11.

| Jar | SHA-256 | Confirmed behaviors | High | Medium | Report |
|---|---|---|---|---|---|
| `skyblock-shopping-list-4.28.1.jar` | `981d8263593f2d4388eb3c789ce8402d5fe7ebcc53056cb1b579eb708e4d3f2a` | 0 | 2 (false positive, see below) | 0 | [HTML](skyblock-shopping-list-4.28.1.html), [JSON](skyblock-shopping-list-4.28.1.json) |
| `neurepoparser-1.9.0.jar` (nested) | `51d99ddebbe055dc2c4e1ccc19e6a90cdb1e29d10e20e8b0751cf62d4011aa71` | 0 | 0 | 0 | [HTML](neurepoparser-1.9.0.html), [JSON](neurepoparser-1.9.0.json) |

Compare the SHA-256 with your copy of the jar (`certutil -hashfile <jar> SHA256` on Windows, `sha256sum <jar>`
elsewhere). These results only hold for a jar with the same hash.

## Notes on the flagged items

- **`possible_minecraft_identity_exfiltration` (high) in `ForgeSpeed` and `recipes/RemoteRecipeFetcher`:** this
  rule fires when a class contains both the text `name` and an HTTP request. Here that is the mayor perk's JSON
  `"name"` field (`ForgeSpeed`) and `File.getName()` / `ZipEntry.getName()` (`RemoteRecipeFetcher`), not the
  player's name. The requests are plain GETs, with no player data,
  to public endpoints: `api.hypixel.net/v2/resources/skyblock/election` (mayor perks),
  `api.hypixel.net/v2/resources/skyblock/items` (item data) and the public NEU item repo on GitHub
  (`codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO`).
- **`neurepoparser-1.9.0.jar`:** NEU's open-source repo parser (`moe.nea:neurepoparser`), declared in
  `build.gradle` and bundled by Fabric Loom. It is byte-for-byte the Maven release plus the `fabric.mod.json`
  Loom adds to every bundled library, which is why the nested jar's hash differs from Maven's.
- **Base64 in `HudPresets`:** HUD share codes are gzipped, Base64 JSON copied to the clipboard for pasting into chat
  or Discord.

## How the scan was run

- Tool: [cev-api/Java-Triage](https://github.com/cev-api/Java-Triage) at commit `f2ed39e` (2026-09-28), a static
  triage scanner for suspicious Java jars and Minecraft mods. It decompiles the jar and looks for obfuscation,
  token/session theft, webhooks, C2 endpoints and known malware families. It never runs the scanned code.
- Decompiler: CFR 0.152 (`cfr-0.152.jar`, SHA-256 `f686e8f3ded377d7bc87d216a90e9e9512df4156e75b06c655a16648ae8765b2`).
- Network off: nothing was uploaded or looked up online.

```
python java_triage.py <jar> --no-network --no-decompiler-download --decompiler cfr --no-progress --out <name>.json --html-out <name>.html
```

Java-Triage only scans a jar's own classes, not the library jars nested in `META-INF/jars/`, so each nested jar was
pulled out and scanned on its own.

Each scan has an HTML report (open it in a browser: sortable tables, source excerpts) and the full JSON output.
In the JSON, the local scan folder is replaced with `<scan-dir>`; nothing else was changed.

## How to read the results

Java-Triage sorts what it finds into tiers. `confirmed_behavior` means it saw malicious code in action (for example,
a token being read and sent out). `suspicious_capability` means the code *could* do something sensitive and is worth
a look, and these rules match broadly. Every scan below has **0 confirmed behaviors** and **0 known malware variants**.
The flagged capabilities were checked by hand against the source; the notes say what each one actually is.

Common false positives in these reports:
- **`minecraft_username_access` / `minecraft_session_access` / `minecraft_uuid_access` (low):** the rule matches
  any file containing the text `name` (or a `getUser()` / `getId()` call), such as `File.getName()`, a JSON field
  called `"name"` or reading the player for the SkyBlock profile folder. None of these mods send the player's name, UUID or session anywhere.
- **`credential_or_identity_field`:** field and method names like `name`, `profile` or `uuid`.
- **`dynamic_execution`:** Brigadier's `.executes(...)` in client command registration, not `Runtime.exec`.
