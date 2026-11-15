# Changelog

## 0.1.0-alpha.2

- Release all addon tickets when an owner logs out.
- Save miner locations and reload them on login, including after an offline server restart.
- Disable allocation and log one error when Forge allows fewer than 13 chunks per ticket.
- Skip window calculations while a miner stays in the same chunk.
- Add server tests for logout, login recovery, offline restarts and an undersized limit.
- Shorten the documentation and describe the new offline behavior.

## 0.1.0-alpha.1

- Add moving chunkloading windows and dock retention for Galacticraft Astro Miners.
- Restore entity-bound tickets after normal server restarts.
- Clean up removed miners and avoid duplicate tickets during restoration.
- Add automated geometry and lifecycle tests.
- Add a disposable Forge server test covering relocation, a four-chunk dock, persistence and cleanup.
