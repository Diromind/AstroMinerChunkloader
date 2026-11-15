package io.github.diromind.astrominerchunkloader;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pure chunk-coordinate calculations; no world access or implicit chunk loading. */
final class ChunkWindow {

    private ChunkWindow() {}

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    static int x(long key) {
        return (int) (key >> 32);
    }

    static int z(long key) {
        return (int) key;
    }

    static Set<Long> around(double x, double z, int baseX, int baseZ) {
        Set<Long> chunks = new LinkedHashSet<>();
        int cx = ((int) Math.floor(x)) >> 4;
        int cz = ((int) Math.floor(z)) >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunks.add(key(cx + dx, cz + dz));
            }
        }
        // The dock is a 2x2x2 cube whose master is its minimum corner.
        for (int bx = baseX >> 4; bx <= (baseX + 1) >> 4; bx++) {
            for (int bz = baseZ >> 4; bz <= (baseZ + 1) >> 4; bz++) {
                chunks.add(key(bx, bz));
            }
        }
        return chunks;
    }
}
