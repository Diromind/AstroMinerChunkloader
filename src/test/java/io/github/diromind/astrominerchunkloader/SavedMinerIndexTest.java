package io.github.diromind.astrominerchunkloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class SavedMinerIndexTest {

    @Test
    public void positionsAndOwnersSurviveNbtRoundTrip() {
        SavedMinerIndex source = new SavedMinerIndex("test");
        UUID miner = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        source.remember(miner, owner, -30, -1234, 4321);
        NBTTagCompound nbt = new NBTTagCompound();
        source.writeToNBT(nbt);
        SavedMinerIndex restored = new SavedMinerIndex("test");
        restored.readFromNBT(nbt);
        SavedMinerIndex.Entry entry = restored.ownedBy(owner)
            .get(0);
        assertEquals(miner, entry.miner);
        assertEquals(-30, entry.dimension);
        assertEquals(-1234, entry.chunkX);
        assertEquals(4321, entry.chunkZ);
        assertTrue(
            restored.ownedBy(UUID.randomUUID())
                .isEmpty());
    }

    @Test
    public void unchangedPositionDoesNotReplaceEntry() {
        SavedMinerIndex index = new SavedMinerIndex("test");
        UUID owner = UUID.randomUUID();
        UUID miner = UUID.randomUUID();
        index.remember(miner, owner, 0, 1, 2);
        SavedMinerIndex.Entry before = index.ownedBy(owner)
            .get(0);
        index.remember(miner, owner, 0, 1, 2);
        assertSame(
            before,
            index.ownedBy(owner)
                .get(0));
    }

    @Test
    public void movingUpdatesEntryAndForgettingRemovesIt() {
        SavedMinerIndex index = new SavedMinerIndex("test");
        UUID owner = UUID.randomUUID();
        UUID miner = UUID.randomUUID();
        index.remember(miner, owner, 0, 1, 2);
        List<SavedMinerIndex.Entry> snapshot = index.ownedBy(owner);
        index.remember(miner, owner, 7, 3, 4);
        assertEquals(1, snapshot.get(0).chunkX);
        assertEquals(
            3,
            index.ownedBy(owner)
                .get(0).chunkX);
        assertEquals(
            7,
            index.ownedBy(owner)
                .get(0).dimension);
        index.forget(miner);
        assertTrue(
            index.ownedBy(owner)
                .isEmpty());
    }
}
