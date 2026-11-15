package io.github.diromind.astrominerchunkloader;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import micdoodle8.mods.galacticraft.planets.asteroids.entities.EntityAstroMiner;

@Mod(
    modid = AstroMinerChunkloader.MODID,
    name = "Astro Miner Chunkloader",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:GalacticraftCore;required-after:GalacticraftMars",
    acceptableRemoteVersions = "*")
public final class AstroMinerChunkloader implements ForgeChunkManager.LoadingCallback {

    public static final String MODID = "astrominerchunkloader";
    private static final Logger LOG = LogManager.getLogger(MODID);

    private final Map<World, LeaseController<Miner, Ticket>> worlds = new IdentityHashMap<>();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ForgeChunkManager.setForcedChunkLoadingCallback(this, this);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (!event.world.isRemote && event.entity instanceof EntityAstroMiner) {
            Miner miner = describe((EntityAstroMiner) event.entity);
            if (miner != null) {
                // Do not load chunks or allocate tickets from inside entity/chunk loading.
                controller(event.world).enqueue(miner);
            }
        }
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (!event.world.isRemote && event.phase == TickEvent.Phase.START) {
            LeaseController<Miner, Ticket> controller = worlds.get(event.world);
            if (controller != null) {
                controller.tick();
            }
        }
    }

    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {
        for (Ticket ticket : tickets) {
            Entity entity = ticket.getEntity();
            if (!(entity instanceof EntityAstroMiner) || entity.isDead || entity.worldObj != world) {
                ForgeChunkManager.releaseTicket(ticket);
                continue;
            }
            Miner miner = describe((EntityAstroMiner) entity);
            if (miner == null) {
                ForgeChunkManager.releaseTicket(ticket);
            } else {
                // Forge loaded the entity before this callback; its join event is already queued.
                controller(world).restore(miner, ticket);
            }
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        // Forge persists tickets on save. Do not release them during dimension/server unload.
        worlds.remove(event.world);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        worlds.clear();
    }

    private static Miner describe(EntityAstroMiner entity) {
        if (entity.isDead) {
            return null;
        }
        NBTTagCompound snapshot = new NBTTagCompound();
        if (!entity.writeToNBTOptional(snapshot) || !snapshot.hasKey("BaseX", 3)
            || !snapshot.hasKey("BaseY", 3)
            || !snapshot.hasKey("BaseZ", 3)) {
            LOG.warn("Cannot register Astro Miner {}: missing dock coordinates", entity.getUniqueID());
            return null;
        }
        return new Miner(entity, snapshot.getInteger("BaseX"), snapshot.getInteger("BaseZ"));
    }

    private LeaseController<Miner, Ticket> controller(final World world) {
        LeaseController<Miner, Ticket> result = worlds.get(world);
        if (result != null) {
            return result;
        }
        result = new LeaseController<>(new LeaseController.Backend<Miner, Ticket>() {

            @Override
            public UUID id(Miner miner) {
                return miner.entity.getUniqueID();
            }

            @Override
            public boolean isLoaded(Miner miner) {
                return !miner.entity.isDead && miner.entity.worldObj == world
                    && world.getEntityByID(miner.entity.getEntityId()) == miner.entity;
            }

            @Override
            public Ticket request(Miner miner) {
                Ticket ticket = ForgeChunkManager
                    .requestTicket(AstroMinerChunkloader.this, world, ForgeChunkManager.Type.ENTITY);
                if (ticket != null) {
                    ticket.bindEntity(miner.entity);
                }
                return ticket;
            }

            @Override
            public void update(Miner miner, Ticket ticket) {
                updateWindow(world, miner, ticket);
            }

            @Override
            public void release(Ticket ticket) {
                ForgeChunkManager.releaseTicket(ticket);
            }

            @Override
            public void denied(Miner miner) {
                LOG.warn(
                    "No Forge chunkloading ticket for Astro Miner {} in dimension {}. "
                        + "Check config/forgeChunkLoading.cfg; retrying every 100 ticks.",
                    id(miner),
                    world.provider.dimensionId);
            }
        });
        worlds.put(world, result);
        return result;
    }

    private static void updateWindow(World world, Miner miner, Ticket ticket) {
        Set<Long> required = ChunkWindow.around(miner.entity.posX, miner.entity.posZ, miner.baseX, miner.baseZ);
        if (required.equals(miner.loaded)) {
            return;
        }
        int limit = ticket.getChunkListDepth();
        if (limit > 0 && required.size() > limit) {
            if (!miner.warnedAboutLimit) {
                LOG.error(
                    "Astro Miner {} needs {} chunks, but its Forge ticket allows {}. "
                        + "Raise maximumChunksPerTicket for {} to at least 13.",
                    miner.entity.getUniqueID(),
                    required.size(),
                    limit,
                    MODID);
                miner.warnedAboutLimit = true;
            }
            return;
        }

        Set<Long> previous = new HashSet<>();
        for (ChunkCoordIntPair chunk : ticket.getChunkList()) {
            previous.add(ChunkWindow.key(chunk.chunkXPos, chunk.chunkZPos));
        }
        // Load new chunks first. forceChunk alone only registers retention, not synchronous loading.
        for (long chunk : required) {
            if (!previous.contains(chunk)) {
                world.getChunkFromChunkCoords(ChunkWindow.x(chunk), ChunkWindow.z(chunk));
            }
        }
        // Remove departed chunks before registering replacements to avoid Forge's FIFO ticket eviction.
        // No world tick/unload can interleave this code on the server thread.
        for (long chunk : previous) {
            if (!required.contains(chunk)) {
                ForgeChunkManager
                    .unforceChunk(ticket, new ChunkCoordIntPair(ChunkWindow.x(chunk), ChunkWindow.z(chunk)));
            }
        }
        for (long chunk : required) {
            if (!previous.contains(chunk)) {
                ForgeChunkManager.forceChunk(ticket, new ChunkCoordIntPair(ChunkWindow.x(chunk), ChunkWindow.z(chunk)));
            }
        }
        miner.loaded = required;
    }

    private static final class Miner {

        final EntityAstroMiner entity;
        final int baseX;
        final int baseZ;
        Set<Long> loaded = new HashSet<>();
        boolean warnedAboutLimit;

        Miner(EntityAstroMiner entity, int baseX, int baseZ) {
            this.entity = entity;
            this.baseX = baseX;
            this.baseZ = baseZ;
        }
    }
}
