# Astro Miner Chunkloader

[![Build](https://github.com/Diromind/AstroMinerChunkloader/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/Diromind/AstroMinerChunkloader/actions/workflows/build-and-test.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.7.10-5C913B)](https://www.minecraft.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Keeps Galacticraft Astro Miners running beyond the player's loaded chunks. Each miner gets a moving 3x3 chunk window, plus the chunks occupied by its dock. The owner can be anywhere on the server, including another dimension.

When the owner logs out, the addon releases all its tickets. A saved index records where the miners stopped, so they can be loaded again when the owner returns, even after a server restart.

## Install

Download the regular JAR from [Releases](https://github.com/Diromind/AstroMinerChunkloader/releases), put it in the server's `mods/` directory and restart. Clients do not need the addon. For single player, put the JAR in the client's `mods/` directory.

Use the regular JAR for installation. The `-dev` and `-sources` files are for development.

| Dependency | Version |
| --- | --- |
| Minecraft | 1.7.10 |
| Forge | 10.13.4.1614 |
| Galacticraft | GTNH fork; compiled against 3.4.34-GTNH |

This is an independent addon. Other Galacticraft builds have not been validated. Releases are currently alpha builds; the full mining, return and recharge cycle still needs testing in a complete GTNH pack.

## Chunkloading

A miner holds at most 13 chunks. The window moves with it and covers the entire 2x2x2 dock, including docks that cross chunk boundaries. Power cables, generators and storage outside those chunks need their own chunkloading.

Logout releases the addon's tickets immediately. Players or other chunkloaders may still keep the same chunks loaded. Galacticraft controls mining speed, energy use, inventory transfers and block-breaking permissions.

The index lives in `<save>/data/astrominerchunkloader_miners.dat`. Keep it with the rest of the world backup. Existing miners are indexed when they load. Miners with saved tickets from alpha.1 are indexed when Forge restores those tickets.

Forge sets the limits in `config/forgeChunkLoading.cfg`. Set `maximumChunksPerTicket` for `astrominerchunkloader` to at least 13. A lower limit disables this addon's chunkloading and logs one error until the server restarts. Forge's standard default is 25.

`maximumTicketCount` must also cover the number of online owners' miners. When no ticket is available, the addon retries every 100 ticks while the miner remains loaded.

## Build and test

Run Gradle with JDK 25. The build provisions its other Java toolchains and produces Java 8 bytecode.

```sh
git clone https://github.com/Diromind/AstroMinerChunkloader.git
cd AstroMinerChunkloader
./gradlew build
```

JARs are written to `build/libs/`.

```sh
./gradlew test
./gradlew spotlessApply
python3 scripts/integration_test.py
```

The integration test starts a local Forge server on `127.0.0.1:25599` and uses a disposable save in `run/integration/`. It refuses to overwrite an existing test world. In the Asteroids dimension, it checks movement across chunks, a dock at a four-chunk intersection, offline restarts, login and logout, ticket cleanup, and a limit below 13 chunks. The simulated owner stays in the Overworld.

## Development

The addon uses Forge events and public APIs. It has no ASM transformers, Mixins, access transformers or reflection. Its build comes from the [GTNH mod template](https://github.com/GTNewHorizons/ExampleMod1.7.10).

See [CONTRIBUTING.md](CONTRIBUTING.md) for the lifecycle and checks. Report problems in [Issues](https://github.com/Diromind/AstroMinerChunkloader/issues), with the Forge and Galacticraft versions and relevant logs.

## License

[MIT](LICENSE). Minecraft and Galacticraft are separate dependencies and are not bundled.
