# Chunk Keeper

**Keep what you built. Regenerate the rest.**

Chunk Keeper is a small desktop tool for Minecraft: Java Edition worlds. It shows a map of every chunk your world has
generated, helps you mark the chunks you want to **keep** (your builds, farms, storage, portals…), and writes a **copy**
of the world that contains only those chunks. Everything else is missing from the copy, so Minecraft generates it again,
with its *current* world generation, the next time a player gets close. That's how you get new biomes, structures and
terrain features into an old world without losing your work.

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
  remove those marks; after an upgrade Chunk Keeper first re-saves the kept chunks with the new version's official server
  so the marks don't come back (see [how it works](docs/how-it-works.md)).
- Selections are saved per world and dimension in the **MCA Selector** selection format, so you can move them between
  both tools.

## Requirements

- Java 21 or newer (any JDK or JRE that includes `jdk.httpserver`, which standard builds do).
- A Java Edition world from 1.18 or newer. Older (1.13–1.17) chunks are read for suggestions, with limited map rendering.

## Use it

1. Download `chunk-keeper-<version>.jar` from the Releases page (or build it, see below).
2. Close Minecraft (reading works while it runs, but applying needs the world closed).
3. Run it:

   ```bash
   java -jar chunk-keeper-0.1.0.jar
   ```

   Your browser opens `http://127.0.0.1:8765/`. The page is served by the program on your own computer; nothing goes
   online.
4. **Pick a world.** Worlds from the vanilla launcher, Prism Launcher, PolyMC, CurseForge and Modrinth are listed, or paste
   any world folder path.
5. **Select.** Check the orange suggestions, click **Keep suggested chunks**, then paint or remove chunks.
   Right-drag moves the map, and the wheel zooms.
6. **Review.** Choose which dimensions to regenerate, run the dry run, and fix anything it flags. Each warning has a
   **Show** button and a **Keep it** button.
7. **Apply.** Pick the output folder (by default, next to the original world) and create the copy. Open the copy in
   Minecraft and explore.

Options: `--port N`, `--no-browser`, `--data-dir DIR`, or pass a world folder to open it right away.

Chunk Keeper keeps its own files (selections and map cache) in `~/ChunkKeeper`, never inside your world.

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
- Half-generated chunks at the edge of explored land keep their old biomes unless you regenerate them too. Chunk Keeper
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
with `-Dchunkkeeper.web=src/main/resources/web` to serve the page straight from the source folder.

Project layout:

```
src/main/java/io/github/estebansa19/chunkkeeper/
  nbt/      NBT reader/writer
  region/   Anvil region files (read, write, LZ4)
  chunk/    chunk parser (1.13+ layouts, 26.x palette variants)
  world/    world folder, dimensions, launcher discovery
  render/   map colors (from the Minecraft client jar when available)
  scan/     region scanner, suggestions, chunk inspector
  plan/     selection files, dry run, apply + verify
  web/      local HTTP server and JSON
src/main/resources/web/   the page (HTML, CSS, JS – no frameworks)
src/test/java/            dependency-free tests
```

## Safety notes

- Chunk Keeper only reads the source world and refuses to apply while the game holds the world's `session.lock`.
- The output folder must be new or empty, and outside the source world.
- After writing, every kept chunk is compared with the source, and every removed chunk is checked to be gone.
- Keep backups of worlds you care about anyway.

## Limitations

- Sharp borders after an upgrade need the official Minecraft server for that version (downloaded from Mojang, or a jar
  you have), a Java new enough to run it (26.x needs Java 25; Prism Launcher and the Minecraft Launcher install one), and
  you accepting the Minecraft EULA. It is a plain server, so it isn't for worlds that need mods for their blocks, biomes
  or dimensions.
- It doesn't pregenerate the regenerated area. Minecraft generates it when you visit; a pregenerator mod or a server
  can do it ahead of time.
- Map colors for modded blocks fall back to a generic color.
- Region files with LZ4 compression (type 4) are supported but have only been tested with synthetic data.
