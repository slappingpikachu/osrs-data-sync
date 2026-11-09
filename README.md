# OSRS Data Sync

A RuneLite plugin that exports your own account's data to JSON files on your computer: skills, quests,
achievement diaries, combat achievements, collection log, bank, kill counts and loot.

Use the files in spreadsheets, scripts or your own tools. The plugin makes no network requests and nothing
leaves your computer.

## Using it

Open the **OSRS Data Sync** panel from the RuneLite sidebar:

- **Export** reads everything again and saves all files immediately.
- **Copy Folder Path** copies the folder with your account's files, to paste into your file browser. The panel
  also shows the folder.
- The panel shows when the files were last saved or exported.

By default, the plugin also saves changes automatically as they happen. To write files only when you select
**Export** (and when you log out), turn off **Save Automatically** in the plugin's settings.

RuneLite's built-in **Loot Tracker** plugin must be enabled for kill counts and loot.

## Output files

Files are written to `.runelite/plugin-data/osrsdatasync/<player name>/`:

| File | Contents |
| --- | --- |
| `progress.json` | Quests, quest points, diary tiers and tasks, combat achievements, skills |
| `killcounts.json` | Loot Tracker totals and official kill counts |
| `clog.json` | Collection log items and counts |
| `containers.json` | Bank, inventory, equipment, rune pouch, Grand Exchange offers, storage items and other storage |
| `loot.jsonl` | One line per kill or reward |

Each `.json` file starts with an `updated` time (ISO 8601, UTC). Files are written to a temporary file and then
renamed, so a program reading them never sees a partial write. Item IDs are the game's item IDs; noted items are
saved as the unnoted item.

### progress.json

| Key | Format |
| --- | --- |
| `player` | Your display name |
| `questPoints` | Quest point total |
| `quests` | `{"Cook's Assistant": 2, ...}`: 0 not started, 1 in progress, 2 finished |
| `diaries` | `{"Ardougne": {"Easy": true, "Medium": false, ...}, ...}` for all 12 regions |
| `diaryTasks` | `{"Ardougne": {"Easy": "1101...", ...}, ...}`: one character per task (1 done, 0 not done), in the order of the OSRS Wiki's task tables |
| `ca` | IDs of completed combat achievement tasks |
| `skills` | `{"Attack": [level, xp], ...}`: real (unboosted) levels |
| `totalLevel`, `totalXp` | Totals |

Quests are read when you log in, when your quest points change and when you select **Export**.

### killcounts.json

| Key | Format |
| --- | --- |
| `killCounts` | `{"Vorkath": 50, ...}` from the game's kill count messages |
| `lootTracker` | `{"NPC": {"Guard": {"kills": 132, "first": ms, "last": ms, "drops": {"995": 2500, ...}}}, "EVENT": {...}, "PICKPOCKET": {...}}` |

`lootTracker` comes from RuneLite's Loot Tracker, plus kills from the current session it has not saved yet. Times
are milliseconds since 1970. The Loot Tracker only counts kills that drop something, and keeps separate records for
each game mode (for example, Leagues).

### clog.json

| Key | Format |
| --- | --- |
| `obtained` | Number of different items obtained |
| `items` | `{"item id": count, ...}` |
| `newByName` | `{"Item name": "time", ...}` for items announced by "New item added to your collection log" |

To read the full log, open the collection log and select **Search** once, which makes the game load every item.
After that, new items and drops of items already in the log are added as they happen.

### containers.json

| Key | Format |
| --- | --- |
| `bank` | `[[item id, quantity, slot], ...]` in bank order; kept from the last time the bank was opened |
| `bankAt` | When the bank was last opened (milliseconds since 1970) |
| `inventory`, `equipment` | `[[item id, quantity, slot], ...]` |
| `runePouch` | `[[item id, quantity], ...]` |
| `ge` | `[{"slot", "state", "id", "total", "done", "price", "spent"}, ...]` for each active offer |
| `storage` | `{"lootingBag": {"name", "how", "at", "items": [[item id, quantity], ...]}, ...}`, see below |

Placeholders and bank fillers are skipped.

`storage` holds items kept inside other items, and storage away from the bank. Each entry has a `name`, the time
it was last read (`at`, milliseconds since 1970) and `how` it was read:

| `how` | Meaning | Storage |
| --- | --- | --- |
| `live` | Always current while you are logged in | Bolt pouch, Dizana's quiver, essence pouches, Master scroll book, Plank sack, Tool leprechaun |
| `open` | As of the last time you opened it | Looting bag, Seed box, Tackle box, Forestry kit, Huntsman's kit, Chugging barrel, Seed vault, Costume room (your house), Potion storage (whenever the bank is open) |
| `check` | As of the last time you selected **Check** on it, or it was emptied | Coal bag, Gem bag, Herb sack, Log basket, Fish barrel |

The game only reports `open` and `check` storage at those moments, so anything added or removed in between shows
after the next open or Check. Potion storage is saved as whole potions (4-dose first).

### loot.jsonl

One JSON object per line, added as each drop happens:

```json
{"t":"2026-11-03T18:04:12.345Z","source":"Vorkath","type":"NPC","value":152340,"items":[[536,2,2950],[1751,1,3100]],"kc":50}
```

`items` holds `[item id, quantity, Grand Exchange price each]`. `kc` is present when the game reported a kill
count. Includes rewards that don't land on the floor, such as raid, Barrows and Gauntlet chests, clue caskets and
pickpocketing. Earlier lines are never changed.

## What it does not record

Other players, chat (apart from kill count and collection log messages), your location, your login details, and
anything outside the game client. The plugin only reads game state: it does not click, type, change menus or draw
overlays.

## Development

Requires Java 11. Build with `gradle build`.

- `OsrsDataSyncPlugin`: reads game data.
- `ExportPanel`: the sidebar panel.
- `Storage`: writes files on a background thread.
- `DiaryTasks`: where the game stores each achievement diary task. Task locations are taken from the
  [Quest Helper](https://github.com/Zoinkwiz/quest-helper) plugin (BSD 2-Clause).

## License

BSD 2-Clause. See [LICENSE](LICENSE).
