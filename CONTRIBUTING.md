# Contributing

Use JDK 25 and the checked-in Gradle wrapper. Run `./gradlew spotlessApply build` before submitting a pull request. Keep the addon server-compatible and preserve Galacticraft's owner and permission checks.

Unit tests cover coordinate calculation and ticket lifecycle with an in-memory backend. Run `python3 scripts/integration_test.py` for the real Forge restart/cleanup test. Its probe is compiled in a separate source set and never bundled in release JARs. These checks do not by themselves prove compatibility with a complete modpack.

For runtime verification, use a disposable world and check:

1. A miner crosses chunk boundaries while its owner is elsewhere, including another dimension.
2. It returns to its dock, recharges and unloads its cargo. Include a dock crossing both X and Z chunk boundaries.
3. The server is stopped and restarted with a miner away from its dock; only one ticket is restored for that miner.
4. Logout pauses mining; login resumes it.
5. Removing the miner releases its ticket. Repeat with multiple miners and overlapping windows.
6. Tight Forge limits produce a clear log message without allocating duplicate tickets.

Include exact Forge and Galacticraft versions and relevant logs in bug reports. Do not attach private server configuration or credentials.
