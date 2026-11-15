# Astro Miner Chunkloader

[![Build](https://github.com/Diromind/AstroMinerChunkloader/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/Diromind/AstroMinerChunkloader/actions/workflows/build-and-test.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.7.10-5C913B)](https://www.minecraft.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A small server-side Forge addon that keeps Galacticraft Astro Miners loaded while they travel away from players. Intended for the GT New Horizons version of Galacticraft.

## Features

- A moving **3×3 chunk window** around each Astro Miner.
- Keeps every chunk occupied by the **2×2×2 dock** loaded, including docks placed across chunk boundaries.
- Retains at most **13 chunks per miner**, rather than loading its entire flight path.
- Restores entity-bound Forge tickets after a normal server restart.
- Releases tickets when a miner dies or is removed from the world.
- Uses Forge events and public APIs: **no ASM, Mixins, access transformers, or reflection**.

## Installation

1. Download the regular JAR from [Releases](https://github.com/Diromind/AstroMinerChunkloader/releases). Do not install the `-dev` or `-sources` JAR.
2. Stop the server and put the JAR in its `mods/` directory.
3. Start the server. Clients do not need the addon.

For single player, install it in the client's `mods/` directory; it acts on the integrated server.

### Requirements

| Component | Target |
| --- | --- |
| Minecraft | 1.7.10 |
| Forge | 10.13.4.1614 |
| Galacticraft | GTNH fork, compiled against **3.4.34-GTNH** |

Other Galacticraft builds have not been validated. This is an independent addon, not an official GTNH or Galacticraft release.

## Behavior and limits

**The owner must still be online.** Galacticraft pauses the miner when its owner logs out; this addon preserves that behavior. The owner may be in another dimension. Tickets remain allocated while the owner is offline so the miner can resume and survive a server restart.

The addon does not change mining speed, energy use, target selection, inventory handling, or block-breaking permission checks. Power generation, cables and output storage outside the dock's chunks need their own chunkloading.

Forge limits tickets in `config/forgeChunkLoading.cfg`. The per-mod settings for `astrominerchunkloader` must allow enough tickets for your miners and at least **13 chunks per ticket**. Standard Forge defaults allow 25 chunks per ticket. If allocation is denied, the addon logs a warning and retries every 100 server ticks; it does not disable Forge limits.

Loading previously unexplored terrain can still cause chunk generation work on the server thread.

## Building

Uses the [GTNH mod template](https://github.com/GTNewHorizons/ExampleMod1.7.10) and RetroFuturaGradle.

```sh
git clone https://github.com/Diromind/AstroMinerChunkloader.git
cd AstroMinerChunkloader
./gradlew build
```

Run Gradle with **JDK 25**. Additional Java toolchains are provisioned by the build. The distributed addon targets Java 8 bytecode; the build JDK is not the game's Java requirement. Output is under `build/libs/`.

```sh
./gradlew test           # Geometry, restoration, deduplication and lifecycle tests
./gradlew spotlessApply  # Apply GTNH formatting
python3 scripts/integration_test.py  # Disposable server: create, move, save, restart, remove
```

The integration test uses its own flat world under `run/integration/`, binds only to `127.0.0.1:25599`, and refuses to overwrite an existing test world. It verifies a dock at a four-chunk intersection, a miner moved beyond its original window, ticket restoration after restart, cleanup after deletion, and preservation of the offline-owner pause. It does not simulate a complete mining trip or replace testing in your modpack.

## How it works

`EntityJoinWorldEvent` queues miners without recursively loading chunks. At `WorldTickEvent.START`, the addon obtains an entity-bound ticket and updates its window before the world updates entities. Dock coordinates are read once through the miner's public NBT serialization API.

New chunks are loaded before old chunks are released. Departed chunks are then unregistered before registering replacements, so a ticket near its configured capacity does not evict retained chunks via Forge's FIFO limit. All operations happen on the server thread.

During restoration, Forge loads the bound entity before invoking the ticket callback. The callback takes precedence over the queued join event, preventing a duplicate ticket. World unload discards Java references without releasing persisted tickets.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build and runtime checks. Report bugs through [GitHub Issues](https://github.com/Diromind/AstroMinerChunkloader/issues).

## License

[MIT](LICENSE). Built using the GTNH mod template. Galacticraft and Minecraft are separate dependencies and are not bundled.
