# Contributing

Use JDK 25 and the checked-in Gradle wrapper. Before opening a pull request, run:

```sh
./gradlew spotlessApply build
python3 scripts/integration_test.py
```

The integration test owns its save in `run/integration/`. Move that directory aside before rerunning it. The test probe uses a separate source set and is excluded from release JARs.

## Lifecycle

`EntityJoinWorldEvent` records a miner's owner and location, then queues it. The handler does not load other chunks. `WorldTickEvent.START` allocates tickets for online owners and updates windows before entities tick. An unchanged chunk position skips the window calculation.

Logout records the current location, clears Galacticraft's cached player reference and releases the tickets before FML removes the player from its online list. The saved index keeps positions without holding chunks or entity references. Login queues recovery for the next server tick, where the addon loads each saved chunk and checks for the miner.

Forge may restore an old entity ticket while its owner is offline. The addon records the miner in the index and releases that ticket. World unload drops Java references; Forge handles saving the world. Normal startup with an offline owner must not retain addon tickets.

A ticket limit below 13 disables allocation for the session and produces one configuration error. Do not silently reduce the window or bypass Forge's limits.

## Checks

Unit tests cover chunk coordinates, index serialization, ticket deduplication, owner suspension and quota retries. The server test checks an owner in another dimension, movement beyond the original window, a dock spanning four chunks, offline restarts, login recovery, logout before removal from the player list, deletion and an undersized limit.

In a full modpack, also check a complete mining trip, return to the dock, recharge and cargo transfer. Test overlapping windows with several miners. Preserve Galacticraft's permission checks.

Include exact versions and relevant logs in bug reports. Leave private server settings and credentials out of attachments.
