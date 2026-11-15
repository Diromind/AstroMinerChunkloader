package io.github.diromind.astrominerchunkloader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import micdoodle8.mods.galacticraft.api.vector.BlockVec3;
import micdoodle8.mods.galacticraft.planets.asteroids.ConfigManagerAsteroids;
import micdoodle8.mods.galacticraft.planets.asteroids.blocks.AsteroidBlocks;
import micdoodle8.mods.galacticraft.planets.asteroids.dimension.WorldProviderAsteroids;
import micdoodle8.mods.galacticraft.planets.asteroids.entities.EntityAstroMiner;

/** Runs only in the disposable save created by scripts/integration_test.py. */
@Mod(modid = "amclintegrationprobe", version = "1", dependencies = "required-after:astrominerchunkloader")
public class IntegrationProbe {

    private static final int BASE = 8191;
    private static final UUID OWNER = UUID.fromString("9c82a16d-f23d-4e8b-81b2-942f491ba0cf");
    private int ticks;
    private EntityAstroMiner miner;
    private EntityPlayerMP owner;
    private Ticket placementTicket;
    private boolean finished;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ForgeChunkManager.setForcedChunkLoadingCallback(
            this,
            (tickets, world) -> { for (Ticket ticket : tickets) ForgeChunkManager.releaseTicket(ticket); });
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        ticks++;
        String phase = System.getProperty("amcl.integration.phase");
        try {
            if ("seed".equals(phase)) seed(false);
            else if ("offline".equals(phase)) offline();
            else if ("login".equals(phase)) loginAndRemove();
            else if ("limit".equals(phase)) seed(true);
            else throw new AssertionError("Unknown phase: " + phase);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.out.println("AMCL_INTEGRATION_FAIL " + phase);
            finished = true;
            MinecraftServer.getServer()
                .initiateShutdown();
        }
    }

    private static WorldServer asteroids() {
        WorldServer world = MinecraftServer.getServer()
            .worldServerForDimension(ConfigManagerAsteroids.dimensionIDAsteroids);
        require(world.provider instanceof WorldProviderAsteroids, "Expected the Asteroids dimension");
        return world;
    }

    private void loginOwner() {
        MinecraftServer server = MinecraftServer.getServer();
        owner = FakePlayerFactory.get(server.worldServerForDimension(0), new GameProfile(OWNER, "AMCLTest"));
        NetworkManager network = new NetworkManager(false);
        // FML allows fake players without a dispatcher, but still expects an attached channel.
        new EmbeddedChannel(network);
        owner.playerNetServerHandler = new NetHandlerPlayServer(server, network, owner) {

            @Override
            public void sendPacket(Packet packet) {}
        };
        server.getConfigurationManager().playerEntityList.add(owner);
        FMLCommonHandler.instance()
            .bus()
            .post(new PlayerEvent.PlayerLoggedInEvent(owner));
    }

    private void logoutOwner() {
        // Exercise the real FML order: event first, removal from the player list afterwards.
        FMLCommonHandler.instance()
            .bus()
            .post(new PlayerEvent.PlayerLoggedOutEvent(owner));
        require(noAddonChunks(), "Logout did not release tickets immediately");
        MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.remove(owner);
    }

    private void seed(boolean undersized) throws Exception {
        if (ticks == 5) {
            loginOwner();
            WorldServer world = asteroids();
            placementTicket = ForgeChunkManager.requestTicket(this, world, ForgeChunkManager.Type.NORMAL);
            require(placementTicket != null, "Missing fixture placement ticket");
            for (int cx = BASE >> 4; cx <= (BASE + 1) >> 4; cx++) {
                for (int cz = BASE >> 4; cz <= (BASE + 1) >> 4; cz++) {
                    world.getChunkFromChunkCoords(cx, cz);
                    ForgeChunkManager.forceChunk(placementTicket, new ChunkCoordIntPair(cx, cz));
                }
            }
            // A previous phase left a full dock. Remove it before placing the next fixture;
            // replacing its blocks one by one would invoke Galacticraft's multiblock teardown.
            for (int x = BASE; x <= BASE + 1; x++) {
                for (int z = BASE; z <= BASE + 1; z++) {
                    for (int y = 200; y <= 201; y++) world.setBlockToAir(x, y, z);
                }
            }
            for (int x = BASE; x <= BASE + 1; x++) {
                for (int z = BASE; z <= BASE + 1; z++) {
                    for (int y = 200; y <= 201; y++) world.setBlock(x, y, z, AsteroidBlocks.blockMinerBase, 0, 3);
                }
            }
        }
        if (ticks == 10) {
            WorldServer world = asteroids();
            require(world.getBlock(BASE, 200, BASE) == AsteroidBlocks.minerBaseFull, "Dock did not form");
            require(
                EntityAstroMiner
                    .spawnMinerAtBase(world, BASE + 1, 201, BASE + 1, 2, new BlockVec3(BASE, 200, BASE), owner),
                "Miner creation failed");
            miner = findMiner(world, null);
            require(miner != null, "Spawned miner not found");
        }
        if (ticks == 15) {
            if (!undersized) verifyWindow(asteroids(), miner);
            ForgeChunkManager.releaseTicket(placementTicket);
            miner.setPosition(BASE + 161, 200, BASE + 1);
        }
        if (undersized && ticks >= 16 && ticks <= 20) {
            require(noAddonChunks(), "Undersized ticket limit still forced chunks");
            MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(miner, miner.worldObj));
        }
        if (ticks == 25) {
            if (undersized) {
                require(noAddonChunks(), "Repeated joins bypassed the undersized ticket limit");
                pass("limit");
            } else {
                require(
                    verifyWindow(asteroids(), miner).getChunkList()
                        .size() == 13,
                    "Dock intersection missing");
                Files.write(
                    Paths.get("expected-miner.txt"),
                    miner.getUniqueID()
                        .toString()
                        .getBytes(StandardCharsets.UTF_8));
                // Leave the owner online: the next process must discard these saved tickets while offline.
                pass("seed");
            }
        }
    }

    private void offline() {
        if (ticks == 20) {
            require(noAddonChunks(), "Restart forced an offline owner's chunks");
            require(
                index().ownedBy(OWNER)
                    .size() == 1,
                "Offline miner was not kept in the saved index");
            pass("offline");
        }
    }

    private void loginAndRemove() throws Exception {
        if (ticks == 5) {
            require(noAddonChunks(), "Offline restart retained tickets");
            loginOwner();
        }
        if (ticks == 15) {
            UUID id = UUID
                .fromString(new String(Files.readAllBytes(Paths.get("expected-miner.txt")), StandardCharsets.UTF_8));
            miner = findMiner(asteroids(), id);
            require(miner != null, "Login did not recover the saved miner");
            verifyWindow(asteroids(), miner);
            require(miner.playerMP == owner, "Owner in another dimension was not resolved");
        }
        if (ticks == 20) {
            logoutOwner();
            require(miner.playerMP == null, "Logout left Galacticraft's cached owner set");
        }
        if (ticks == 25) {
            require(noAddonChunks(), "Tickets were reacquired after logout");
            require(
                index().ownedBy(OWNER)
                    .size() == 1,
                "Logout lost the miner's position");
            loginOwner();
        }
        if (ticks == 35) {
            UUID id = UUID
                .fromString(new String(Files.readAllBytes(Paths.get("expected-miner.txt")), StandardCharsets.UTF_8));
            miner = findMiner(asteroids(), id);
            require(miner != null, "Second login failed to resume the miner");
            verifyWindow(asteroids(), miner);
            miner.setDead();
        }
        if (ticks == 40) {
            require(noAddonChunks(), "Dead miner's chunks are still forced");
            require(
                index().ownedBy(OWNER)
                    .isEmpty(),
                "Dead miner remains in the saved index");
            pass("login");
        }
    }

    private static SavedMinerIndex index() {
        return SavedMinerIndex.get(
            MinecraftServer.getServer()
                .worldServerForDimension(0));
    }

    private static boolean noAddonChunks() {
        for (WorldServer world : DimensionManager.getWorlds()) {
            for (Ticket ticket : ForgeChunkManager.getPersistentChunksFor(world)
                .values()) {
                if (AstroMinerChunkloader.MODID.equals(ticket.getModId())) return false;
            }
        }
        return true;
    }

    private static EntityAstroMiner findMiner(WorldServer world, UUID id) {
        for (Object object : world.loadedEntityList) {
            if (object instanceof EntityAstroMiner) {
                EntityAstroMiner entity = (EntityAstroMiner) object;
                if (id == null || id.equals(entity.getUniqueID())) return entity;
            }
        }
        return null;
    }

    private static Ticket verifyWindow(WorldServer world, EntityAstroMiner miner) {
        Set<Ticket> tickets = new HashSet<>();
        for (Ticket ticket : ForgeChunkManager.getPersistentChunksFor(world)
            .values()) {
            Entity entity = ticket.getEntity();
            if (entity == miner) tickets.add(ticket);
        }
        require(tickets.size() == 1, "Expected one ticket, found " + tickets.size());
        Ticket ticket = tickets.iterator()
            .next();
        Set<Long> actual = new HashSet<>();
        for (ChunkCoordIntPair chunk : ticket.getChunkList()) {
            actual.add(ChunkWindow.key(chunk.chunkXPos, chunk.chunkZPos));
            require(
                world.getChunkProvider()
                    .chunkExists(chunk.chunkXPos, chunk.chunkZPos),
                "Forced chunk not loaded");
        }
        require(actual.equals(ChunkWindow.around(miner.posX, miner.posZ, BASE, BASE)), "Wrong chunk window");
        return ticket;
    }

    private void pass(String phase) {
        System.out.println("AMCL_INTEGRATION_PASS " + phase);
        finished = true;
        MinecraftServer.getServer()
            .initiateShutdown();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
