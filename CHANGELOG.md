# Changelog

## 0.1.0 (2026-11-02)

First version, named Upkeep (it was called Chunk Keeper while it was being built).

- Map of every generated chunk per dimension, colored from the installed Minecraft version's textures.
- Paint or box-select the chunks to keep; suggestions for builds, storage, pets, villagers, portals, players and farms.
- Dry run with warnings: work that would be regenerated, portal links, cut structures, water at the seams, and chunks
  Upkeep can't read (damaged, or last saved before 1.13).
- Apply writes a verified copy with only the kept chunks; blended or sharp borders (sharp after an upgrade re-saves the
  kept chunks once with that version's official server).
- Windows app (`Upkeep.exe` with its own Java) with a small "Upkeep is running" window; a second start opens the page of
  the one already running.
- Hardening: the "is Minecraft running?" check opens `session.lock` read-only, the page can't be framed by other sites,
  the output folder must be a full path, a half-written copy is named in the error, damaged LZ4 data can't make Upkeep
  allocate gigabytes, a server that stops responding is closed after 15 minutes, failed downloads leave nothing behind,
  and a double click can't start two copies.
