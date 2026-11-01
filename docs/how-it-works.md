# How regeneration works (and what the borders look like)

These notes come from testing Minecraft 26.1.2 → 26.3 on the same seed with dedicated servers, plus reading both
game jars (data generator reports and the world upgrader code).

## Biomes come from the seed plus the game version's biome table

Since 1.18 the Overworld uses the `multi_noise` biome source. For every 4×4×4 cell the game samples six noise values
from the seed: temperature, humidity, continentalness, erosion, weirdness and depth. The cell gets the biome whose box in
the **parameter list** (preset `minecraft:overworld`, about 7,600 boxes) is nearest to that point.

- The save stores only the preset name, not the list. Each game version brings its own list.
- Same seed means the same noise, so the climate at every spot stays the same across versions. Only the biome assigned to
  that climate changes.
- A new biome either takes over boxes from an existing biome or adds new boxes. Other biomes don't move.

Example: from 26.1.2 to 26.3, two biomes were added and nothing else changed.

| change | detail |
|---|---|
| `plains → dappled_forest` (26.3) | exactly 90 plains boxes replaced one for one (cool, dry plains) |
| `sulfur_caves` (26.2) | one new box (deep underground, low weirdness, high erosion) |

On a 2,304-chunk test area, 7.2 % of biome cells changed. Nearly all were `plains → dappled_forest` or `… → sulfur_caves`,
plus about 0.006 % tie-breaks at biome borders. Terrain height was identical.

## What happens to chunks

| chunk | result |
|---|---|
| already generated and kept | never changes |
| deleted, then regenerated far from kept chunks | identical to a fresh world of the new version |
| regenerated next to kept chunks the new version marks "old" | **blended**, see below |
| unfinished edge chunk (generated only partway by the old version) | finished by the new version but **keeps the old biomes** |
| regenerated with the same version that generated the kept chunks | identical to a fresh world, with no blending |

## Blending

When a new version loads an Overworld chunk older than a certain data version, it adds a `blending_data` tag. The marker
moves with releases that change world generation: it was at DataVersion 4295 in 26.1.2 and 4997 in 26.3. New chunks
generated next to tagged chunks are blended:

| distance from kept chunk | cells with the old biome (where old ≠ new) |
|---:|---:|
| 1 chunk | 90 % |
| 2 chunks | 80 % |
| 3 chunks | 59 % |
| 4 chunks | 27 % |
| 5 chunks | 2 % |
| 6+ chunks | 0 % |

The terrain is reshaped too, even when the terrain generator did not change. On average it is about 4 blocks off from
the true terrain within about 100 blocks of the seam, and up to 50+ blocks at single spots. The seam itself is smooth: no
cliff. Some odd biome streaks can appear.

The Nether and End are never blended.

**Sharp borders** (an Apply option) removes the tag from kept chunks. New chunks then generate exactly as in a fresh world
right up to your builds. The cost is straight edges along chunk lines, and cliffs if the new version changed terrain shape.

## Regenerated neighbours can touch the edge of kept chunks

When Minecraft generates a chunk, some of its features reach into neighbouring chunks: ore and stone-variety blobs
(granite, diorite, andesite, coal ore, gravel and dirt pockets) and trees. That also happens when the neighbour is a chunk you
kept. In an end-to-end test (26.3 world, 541 kept chunks next to 3,555 regenerated ones), about 9,400 blocks changed inside
kept chunks:

- only natural stone, ore and soil blocks, never player-type blocks
- nearly all within 1–5 blocks of the border, and none deeper than 8 blocks

A build made of stone-type blocks right at the edge of the kept area could get patched, which is why suggestions add a
**1-chunk safety margin** by default.

In the same test, every removed chunk was regenerated with biomes identical to the original (same version and seed), and the
regenerated terrain matched the original apart from the order-dependent spots above (0.3 % of blocks).

## Other things at the seams

- **Water and lava** at the edge of a kept chunk can flow into lower regenerated terrain. The dry run lists these edges.
- **Structures** store their start in one chunk and are referenced from the others. If the start is regenerated but some
  pieces are kept, the structure is generated again and may not line up. The dry run warns about this; mineshafts and
  other minor structures are left out of the warnings.
- **Farms** that depend on a structure (fortress, monument, witch hut, pillager outpost) need its start chunk kept.
  Suggestions add it automatically.
- **Portals**: the dry run warns when a kept portal's partner in the other dimension would be regenerated.
- **End**: keep the main island, which holds the exit portal, the pillars and the dragon fight data, plus the outer chunks
  where your gateways exit. Regenerated outer islands bring back end cities with fresh loot.

## Sources

- Minecraft Wiki: [World generation](https://minecraft.wiki/w/World_generation), [Java Edition 22w11a](https://minecraft.wiki/w/Java_Edition_22w11a)
  (3D biome blending; `blending_data` marks old chunks), [Java Edition 21w39a](https://minecraft.wiki/w/Java_Edition_21w39a),
  [Sulfur Caves](https://minecraft.wiki/w/Sulfur_Caves), [Dappled Forest](https://minecraft.wiki/w/Dappled_Forest).
- [Minecraft 1.18 Pre-release 1](https://www.minecraft.net/en-us/article/minecraft-1-18-pre-release-1) (biome blending FAQ).
- Discontinued Features Wiki: [Old Blending Chunks](https://mcdf.wiki.gg/wiki/Java_Edition:Old_Blending_Chunks).
- Mojang bug tracker: MC-244340, MC-258585.
