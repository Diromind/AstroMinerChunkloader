package io.github.diromind.astrominerchunkloader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
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
import cpw.mods.fml.common.gameevent.PlayerEvent;
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
    private final Set<UUID> onlineOwners = new HashSet<>();
    private final Set<UUID> loggedOutOwners = new HashSet<>();
    private final Set<UUID> pendingOwners = new HashSet<>();
    private final Set<UUID> warnedAboutTickets = new HashSet<>();
    private boolean loadingEnabled = true;
    private boolean warnedAboutLimit;

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
                remember(miner);
                // Do not load chunks or allocate tickets from inside entity/chunk loading.
                controller(event.world).enqueue(miner);
            }
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        UUID owner = event.player.getUniqueID();
        loggedOutOwners.remove(owner);
        pendingOwners.add(owner);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID owner = event.player.getUniqueID();
        // FML fires logout before removing the player from playerEntityList.
        loggedOutOwners.add(owner);
        onlineOwners.remove(owner);
        pendingOwners.remove(owner);
        for (LeaseController<Miner, Ticket> controller : new ArrayList<>(worlds.values())) {
            controller.releaseMatching(miner -> miner.owner.equals(owner));
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Set<UUID> present = new HashSet<>();
        for (EntityPlayerMP player : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            present.add(player.getUniqueID());
        }
        loggedOutOwners.retainAll(present);
        present.removeAll(loggedOutOwners);
        for (UUID owner : present) {
            if (!onlineOwners.contains(owner)) {
                pendingOwners.add(owner);
            }
        }
        onlineOwners.clear();
        onlineOwners.addAll(present);
        loadingEnabled = checkCapacity();
        if (!pendingOwners.isEmpty()) {
            List<UUID> owners = new ArrayList<>(pendingOwners);
            pendingOwners.clear();
            if (loadingEnabled) {
                for (UUID owner : owners) {
                    if (ownerOnline(owner)) {
                        recoverOwner(owner);
                    }
                }
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
                remember(miner);
                if (checkCapacity() && ownerOnline(miner.owner)) {
                    // The join event is already queued; adopt its existing ticket.
                    controller(world).restore(miner, ticket);
                } else {
                    ForgeChunkManager.releaseTicket(ticket);
                }
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
        onlineOwners.clear();
        loggedOutOwners.clear();
        pendingOwners.clear();
        warnedAboutTickets.clear();
        warnedAboutLimit = false;
        loadingEnabled = true;
    }

    private static Miner describe(EntityAstroMiner entity) {
        if (entity.isDead) {
            return null;
        }
        NBTTagCompound snapshot = new NBTTagCompound();
        if (!entity.writeToNBTOptional(snapshot) || !snapshot.hasKey("BaseX", 3)
            || !snapshot.hasKey("BaseY", 3)
            || !snapshot.hasKey("BaseZ", 3)
            || !snapshot.hasKey("playerUUIDMost", 4)
            || !snapshot.hasKey("playerUUIDLeast", 4)) {
            LOG.warn("Cannot load Astro Miner {}: its saved dock or owner is missing", entity.getUniqueID());
            return null;
        }
        UUID owner = new UUID(snapshot.getLong("playerUUIDMost"), snapshot.getLong("playerUUIDLeast"));
        return new Miner(entity, owner, snapshot.getInteger("BaseX"), snapshot.getInteger("BaseZ"));
    }

    private boolean ownerOnline(UUID owner) {
        return onlineOwners.contains(owner) && !loggedOutOwners.contains(owner);
    }

    private boolean checkCapacity() {
        int limit = ForgeChunkManager.getMaxChunkDepthFor(MODID);
        if (limit > 0 && limit < 13) {
            if (!warnedAboutLimit) {
                LOG.error(
                    "Chunkloading is disabled: {} allows {} chunks per ticket; this addon needs 13. "
                        + "Update config/forgeChunkLoading.cfg and restart the server.",
                    MODID,
                    limit);
                warnedAboutLimit = true;
            }
            return false;
        }
        return true;
    }

    private static SavedMinerIndex index() {
        return SavedMinerIndex.get(
            MinecraftServer.getServer()
                .worldServerForDimension(0));
    }

    private static void remember(Miner miner) {
        if (miner.entity.isDead) {
            index().forget(miner.entity.getUniqueID());
        } else {
            index().remember(
                miner.entity.getUniqueID(),
                miner.owner,
                miner.entity.dimension,
                ((int) Math.floor(miner.entity.posX)) >> 4,
                ((int) Math.floor(miner.entity.posZ)) >> 4);
        }
    }

    private void recoverOwner(UUID owner) {
        for (SavedMinerIndex.Entry entry : index().ownedBy(owner)) {
            if (!DimensionManager.isDimensionRegistered(entry.dimension)) {
                LOG.warn("Cannot resume Astro Miner {}: dimension {} is not registered", entry.miner, entry.dimension);
                continue;
            }
            WorldServer world = MinecraftServer.getServer()
                .worldServerForDimension(entry.dimension);
            world.getChunkFromChunkCoords(entry.chunkX, entry.chunkZ);
            Miner found = null;
            for (Object object : world.loadedEntityList) {
                if (object instanceof EntityAstroMiner && ((Entity) object).getUniqueID()
                    .equals(entry.miner)) {
                    found = describe((EntityAstroMiner) object);
                    break;
                }
            }
            if (found == null) {
                index().forget(entry.miner);
            } else {
                controller(world).enqueue(found);
            }
        }
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
            public boolean canRun(Miner miner) {
                return loadingEnabled && ownerOnline(miner.owner);
            }

            @Override
            public void beforeRelease(Miner miner) {
                if (!ownerOnline(miner.owner)) {
                    // Clear the cached player before releasing chunks; its UUID remains unchanged.
                    miner.entity.playerMP = null;
                }
                remember(miner);
            }

            @Override
            public Ticket request(Miner miner) {
                Ticket ticket = ForgeChunkManager
                    .requestTicket(AstroMinerChunkloader.this, world, ForgeChunkManager.Type.ENTITY);
                if (ticket != null) {
                    ticket.bindEntity(miner.entity);
                    warnedAboutTickets.remove(id(miner));
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
                if (warnedAboutTickets.add(id(miner))) {
                    LOG.warn(
                        "No chunkloading ticket for Astro Miner {} in dimension {}. "
                            + "Check config/forgeChunkLoading.cfg. Retrying every 100 ticks while it is loaded.",
                        id(miner),
                        world.provider.dimensionId);
                }
            }
        });
        worlds.put(world, result);
        return result;
    }

    private static void updateWindow(World world, Miner miner, Ticket ticket) {
        int chunkX = ((int) Math.floor(miner.entity.posX)) >> 4;
        int chunkZ = ((int) Math.floor(miner.entity.posZ)) >> 4;
        if (chunkX == miner.windowX && chunkZ == miner.windowZ) {
            return;
        }
        remember(miner);
        Set<Long> required = ChunkWindow.around(miner.entity.posX, miner.entity.posZ, miner.baseX, miner.baseZ);
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
        miner.windowX = chunkX;
        miner.windowZ = chunkZ;
    }

    private static final class Miner {

        final EntityAstroMiner entity;
        final UUID owner;
        final int baseX;
        final int baseZ;
        int windowX = Integer.MIN_VALUE;
        int windowZ = Integer.MIN_VALUE;

        Miner(EntityAstroMiner entity, UUID owner, int baseX, int baseZ) {
            this.entity = entity;
            this.owner = owner;
            this.baseX = baseX;
            this.baseZ = baseZ;
        }
    }
}
