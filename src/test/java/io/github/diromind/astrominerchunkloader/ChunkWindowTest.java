package io.github.diromind.astrominerchunkloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

public class ChunkWindowTest {

    @Test
    public void keepsOnlyNineChunksWhenDockIsNearby() {
        Set<Long> chunks = ChunkWindow.around(8, 8, 0, 0);
        assertEquals(9, chunks.size());
        assertTrue(chunks.contains(ChunkWindow.key(-1, -1)));
        assertTrue(chunks.contains(ChunkWindow.key(1, 1)));
    }

    @Test
    public void keepsAllFourChunksOfDockAtIntersection() {
        Set<Long> chunks = ChunkWindow.around(512, 512, 15, 15);
        assertEquals(13, chunks.size());
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                assertTrue(chunks.contains(ChunkWindow.key(x, z)));
            }
        }
    }

    @Test
    public void negativeFractionBelongsToNegativeChunk() {
        Set<Long> chunks = ChunkWindow.around(-0.01, -0.01, -1, -1);
        assertEquals(9, chunks.size());
        assertTrue(chunks.contains(ChunkWindow.key(-2, -2)));
        assertFalse(chunks.contains(ChunkWindow.key(1, 1)));
    }

    @Test
    public void windowMovesAtBoundaryAndDoesNotRetainTrail() {
        Set<Long> before = ChunkWindow.around(15.99, 0, 1000, 1000);
        Set<Long> after = ChunkWindow.around(16, 0, 1000, 1000);
        assertTrue(before.contains(ChunkWindow.key(-1, 0)));
        assertFalse(after.contains(ChunkWindow.key(-1, 0)));
        assertTrue(after.contains(ChunkWindow.key(2, 0)));
        assertEquals(10, after.size());
    }

    @Test
    public void signedCoordinatesSurvivePacking() {
        int[] values = { Integer.MIN_VALUE, -12345, -1, 0, 1, 12345, Integer.MAX_VALUE };
        for (int x : values) {
            for (int z : values) {
                long key = ChunkWindow.key(x, z);
                assertEquals(x, ChunkWindow.x(key));
                assertEquals(z, ChunkWindow.z(key));
            }
        }
    }
}
