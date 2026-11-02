# Upkeep

**Keep what you built. Regenerate the rest.**

Upkeep brings an old Minecraft: Java Edition world up to date with a new version's terrain without losing your work. It
shows a map of every chunk your world has generated, helps you mark the chunks you want to **keep** (your builds, farms,
storage, portals…), and writes a **copy** of the world that contains only those chunks. Everything else is missing from
the copy, so Minecraft generates it again, with its *current* world generation, the next time a player gets close. That's
how you get new biomes, structures and terrain features into an old world. The borders between kept and new land can be
blended (Minecraft's default) or sharp.

Your original world is never modified.

## Features

- **Map of generated chunks** for every dimension (Overworld, Nether, End, and datapack dimensions), rendered top-down from
  the region files. Block colors come from your installed Minecraft version, so new blocks look right without updates.
- **Paint or box-select** the chunks to keep, with undo/redo, brush sizes, a chunk grid, and coordinates on hover.
- **Suggestions** for chunks that look like your work:
  - player-placed blocks outside natural structures (mineshafts, villages and the like are recognized and skipped)
  - containers with items, beacons, and signs with text
  - tamed, named or leashed mobs, villagers you traded with, item frames and armor stands
  - nether portals and activated End portals
  - player positions, respawn points and world spawn
  - the main End island and the End gateways you used
  - the structure starts your farms depend on
  - places where players spent a lot of time
  - an optional safety margin around all of the above
- **Dry run** before anything is written. It flags chunks that would be regenerated even though they look like your work,
  portals whose partner would be lost, structures cut in two, and water at the edge of kept land.
- **Apply to a copy.** Kept chunks are copied byte for byte into fresh, compact region files, and their entities and POI
  data come along. Then the copy is re-read and verified.
- **Blended or sharp borders.** By default Minecraft blends new land into kept chunks it considers old. Sharp borders
  remove those marks; after an upgrade Upkeep first re-saves the kept chunks with the new version's official server
  so the marks don't come back (see [how it works](docs/how-it-works.md)).
- Selections are saved per world and dimension in the **MCA Selector** selection format, so you can move them between
  both tools.

## Requirements

- **Windows:** nothing. The Windows app brings its own Java.
- **macOS / Linux:** Java 21 or newer (any JDK or JRE that includes `jdk.httpserver`, which standard builds do).
- A Java Edition world from 1.18 or newer. Older (1.13–1.17) chunks are read for suggestions, with limited map rendering.

## Use it

### Install

- **Windows:** on the [Releases page](https://github.com/estebansa19/upkeep/releases/latest), download
  `Upkeep-<version>-windows-x64.zip`. Right-click it, choose **Extract All…**, and open the extracted `Upkeep` folder.
  Double-click `Upkeep.exe` (keep it in that folder: it needs the `app` and `runtime` folders next to it). To find it
  again later, right-click `Upkeep.exe` and choose **Pin to Start**.
  Until the Windows app is code-signed (see [Code signing policy](#code-signing-policy)), Windows may say "Windows
  protected your PC" the first time: click **More info**, then **Run anyway**.
- **macOS / Linux:** download `upkeep-<version>.jar` and run `java -jar upkeep-<version>.jar` (Java 21 or newer).

To check a download, compare its SHA-256 with `SHA256SUMS.txt` on the same release.

### Regenerate a world

1. **Close Minecraft.** Upkeep can read a world while the game runs, but it only writes the copy once the world is closed.
2. **Start Upkeep.** A small window says it's running, and your browser opens `http://127.0.0.1:8765/`. The page is
   served by Upkeep on your own computer; nothing goes online. Keep the small window open while you work; closing it
   stops Upkeep. Starting Upkeep again while it runs just opens the page again.
3. **Pick a world.** Worlds from the vanilla launcher, Prism Launcher, PolyMC, CurseForge and Modrinth are listed, or paste
   any world folder path (the folder that holds `level.dat`). Upkeep reads the whole world once to draw the map; big
   worlds take a while the first time, then it's cached.
4. **Select what to keep.** Chunks that look like your work are outlined in orange. Untick categories you don't care
   about, click **Keep suggested chunks**, then paint more chunks with the keep brush (`B`) or remove some with the
   remove brush (`E`). Right-drag moves the map, the wheel zooms, `I` shows what's in a chunk. Your selection is saved
   automatically.
5. **Review.** On the Review tab, switch on the dimensions to regenerate (the others are copied as they are), choose the
   Minecraft version you'll play the copy in, and click **Run dry run**. Fix anything it flags: each warning has a
   **Show** button and a **Keep it** button.
6. **Apply.** On the Apply tab, check the new world's folder and name (by default it goes next to the original, as
   "<world> (regenerated)"), choose **Blended** or **Sharp** borders, and click **Create regenerated copy**. When it
   says "Done and verified", the copy is ready.
7. **Play.** Open Minecraft (the same launcher and instance), pick the new world, and explore: every chunk you didn't
   keep is generated fresh as you get near it. Your original world is still there, unchanged.

Options: `--port N`, `--no-browser`, `--no-window` (console only, stop with Ctrl+C), `--data-dir DIR`, or pass a world
folder to open it right away.

Upkeep keeps its own files (selections and map cache) in `~/Upkeep`, never inside your world.

### Keyboard

| key | action |
|---|---|
| `B` / `E` | keep brush / remove brush |
| `K` / `X` | keep a rectangle / remove a rectangle |
| `I` / `H` | inspect a chunk / move the map |
| `[` `]` | brush size |
| `Ctrl+Z` / `Ctrl+Y` | undo / redo |
| `F` | fit to world |
| `G` / `S` / `P` / `T` | toggle grid / suggestions / regenerate preview / time-spent heat map |

## What to expect after regenerating

The short version, measured on real worlds (details and numbers in [docs/how-it-works.md](docs/how-it-works.md)):

- Regenerated chunks get the new version's biomes and features, because the seed stays the same and only the game's
  biome table changes.
- Kept chunks never change.
- If the new version marks your kept chunks as "old", it blends new terrain into them. The old biome then continues about
  5 chunks into the regenerated land, and terrain is smoothed for about 100 blocks. A version that changed world generation
  marks every older chunk when it loads it (26.1.2 → 26.3 did), so expect this ring after such an upgrade, unless you
  choose sharp borders.
- Half-generated chunks at the edge of explored land keep their old biomes unless you regenerate them too. Upkeep
  removes them unless you keep them.
- New chunks can place ore, stone blobs and trees up to about 8 blocks into neighbouring kept chunks. Natural blocks are
  the only ones affected, but keep the default 1-chunk safety margin around builds.

## Build from source

Only a JDK is needed (no Gradle or Maven):

```powershell
.\build.ps1            # Windows
```

```bash
./build.sh             # Linux / macOS
```

The jar ends up in `build/`. Tests run as part of the build (`-SkipTests` / `--skip-tests` to skip). During UI work, run
with `-Dupkeep.web=src/main/resources/web` to serve the page straight from the source folder.

`.\build.ps1 -Package` also builds the Windows app (`build\dist\Upkeep\Upkeep.exe`) with `jpackage` and zips it. For a
small app it needs a JDK that can link a runtime, such as Eclipse Temurin 25; with other JDKs it copies the whole JDK
(fine for local testing). Releases are built by GitHub Actions, see [docs/releasing.md](docs/releasing.md).

Project layout:

```
src/main/java/io/github/estebansa19/upkeep/
  nbt/      NBT reader/writer
  region/   Anvil region files (read, write, LZ4)
  chunk/    chunk parser (1.13+ layouts, 26.x palette variants)
  world/    world folder, dimensions, launcher discovery
  render/   map colors (from the Minecraft client jar when available)
  scan/     region scanner, suggestions, chunk inspector
  plan/     selection files, dry run, apply + verify
  web/      local HTTP server and JSON
  ui/       the small "Upkeep is running" window and the app icon
src/main/resources/web/   the page (HTML, CSS, JS – no frameworks)
src/test/java/            dependency-free tests
```

## Safety notes

- Upkeep only reads the source world (even the "is Minecraft running?" check opens `session.lock` read-only) and refuses
  to apply while the game holds the world.
- The output folder must be new or empty, and outside the source world. If writing fails halfway, Upkeep says where the
  unfinished copy is.
- After writing, every kept chunk is compared with the source, and every removed chunk is checked to be gone.
- The page only answers your own computer (`127.0.0.1`), checks the `Host` header, and every request needs a random key
  that changes each time Upkeep starts, so other websites can't use it.
- Keep backups of worlds you care about anyway.

## Privacy

Upkeep has no telemetry and sends nothing anywhere. The only time it goes online is when you click **Download from
Mojang** for Sharp borders: it then downloads the official server from Mojang (`*.mojang.com`, checked against Mojang's
SHA-1). Selections and the map cache stay in `~/Upkeep` on your computer.

## Code signing policy

Windows releases are built from this repository by GitHub Actions ([release workflow](.github/workflows/release.yml)),
with nothing added by hand. Free code signing by [SignPath.io](https://signpath.io), with a certificate by the
[SignPath Foundation](https://signpath.org), is being set up; until then the Windows app is unsigned and Windows may warn
before the first start. Each release lists SHA-256 checksums.

- Committers and reviewers: [estebansa19](https://github.com/estebansa19)
- Approvers: [estebansa19](https://github.com/estebansa19)

This program will not transfer any information to other networked systems unless specifically requested by the user or
the person installing or operating it (see [Privacy](#privacy)).

## License

[MIT](LICENSE). Upkeep is not an official Minecraft product and is not approved by or associated with Mojang or
Microsoft.

## Limitations

- Sharp borders after an upgrade need the official Minecraft server for that version (downloaded from Mojang, or a jar
  you have), a Java new enough to run it (26.x needs Java 25; Prism Launcher and the Minecraft Launcher install one), and
  you accepting the Minecraft EULA. It is a plain server, so it isn't for worlds that need mods for their blocks, biomes
  or dimensions.
- It doesn't pregenerate the regenerated area. Minecraft generates it when you visit; a pregenerator mod or a server
  can do it ahead of time.
- Map colors for modded blocks fall back to a generic color.
- Chunks last saved before 1.13 (and damaged chunks) can't be read: the dry run lists them, so you can check them yourself.
- Region files with LZ4 compression (type 4) are supported but have only been tested with synthetic data.
- Very large worlds (tens of thousands of region files) need a lot of memory for the map.
