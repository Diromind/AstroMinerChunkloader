package io.github.diromind.astrominerchunkloader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/** Positions survive without tickets, so an offline owner's miners can be unloaded completely. */
public final class SavedMinerIndex extends WorldSavedData {

    private static final String NAME = AstroMinerChunkloader.MODID + "_miners";
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public SavedMinerIndex(String name) {
        super(name);
    }

    static SavedMinerIndex get(World overworld) {
        SavedMinerIndex index = (SavedMinerIndex) overworld.perWorldStorage.loadData(SavedMinerIndex.class, NAME);
        if (index == null) {
            index = new SavedMinerIndex(NAME);
            overworld.perWorldStorage.setData(NAME, index);
        }
        return index;
    }

    void remember(UUID miner, UUID owner, int dimension, int chunkX, int chunkZ) {
        Entry existing = entries.get(miner);
        if (existing != null && existing.owner.equals(owner)
            && existing.dimension == dimension
            && existing.chunkX == chunkX
            && existing.chunkZ == chunkZ) {
            return;
        }
        entries.put(miner, new Entry(miner, owner, dimension, chunkX, chunkZ));
        markDirty();
    }

    void forget(UUID miner) {
        if (entries.remove(miner) != null) {
            markDirty();
        }
    }

    List<Entry> ownedBy(UUID owner) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.owner.equals(owner)) {
                result.add(entry);
            }
        }
        return result;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        entries.clear();
        NBTTagList list = nbt.getTagList("Miners", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            UUID miner = new UUID(tag.getLong("MinerMost"), tag.getLong("MinerLeast"));
            UUID owner = new UUID(tag.getLong("OwnerMost"), tag.getLong("OwnerLeast"));
            entries.put(
                miner,
                new Entry(miner, owner, tag.getInteger("Dimension"), tag.getInteger("X"), tag.getInteger("Z")));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Entry entry : entries.values()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("MinerMost", entry.miner.getMostSignificantBits());
            tag.setLong("MinerLeast", entry.miner.getLeastSignificantBits());
            tag.setLong("OwnerMost", entry.owner.getMostSignificantBits());
            tag.setLong("OwnerLeast", entry.owner.getLeastSignificantBits());
            tag.setInteger("Dimension", entry.dimension);
            tag.setInteger("X", entry.chunkX);
            tag.setInteger("Z", entry.chunkZ);
            list.appendTag(tag);
        }
        nbt.setTag("Miners", list);
    }

    static final class Entry {

        final UUID miner;
        final UUID owner;
        final int dimension;
        final int chunkX;
        final int chunkZ;

        Entry(UUID miner, UUID owner, int dimension, int chunkX, int chunkZ) {
            this.miner = miner;
            this.owner = owner;
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
}
