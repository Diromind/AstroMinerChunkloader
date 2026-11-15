package io.github.diromind.astrominerchunkloader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import micdoodle8.mods.galacticraft.api.vector.BlockVec3;
import micdoodle8.mods.galacticraft.planets.asteroids.blocks.AsteroidBlocks;
import micdoodle8.mods.galacticraft.planets.asteroids.entities.EntityAstroMiner;

/** Executed only in a fresh, disposable development world by scripts/integration_test.py. */
@Mod(modid = "amclintegrationprobe", version = "1", dependencies = "required-after:astrominerchunkloader")
public class IntegrationProbe {

    private static final int BASE = 8191;
    private int ticks;
    private EntityAstroMiner miner;
    private boolean finished;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) {
            return;
        }
        ticks++;
        String phase = System.getProperty("amcl.integration.phase");
        try {
            WorldServer world = MinecraftServer.getServer()
                .worldServerForDimension(0);
            if ("seed".equals(phase)) {
                seed(world);
            } else if ("restore".equals(phase)) {
                restore(world);
            } else {
                throw new AssertionError("Unknown integration phase: " + phase);
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.out.println("AMCL_INTEGRATION_FAIL " + phase);
            finished = true;
            MinecraftServer.getServer()
                .initiateShutdown();
        }
    }

    private void seed(WorldServer world) throws Exception {
        if (ticks == 5) {
            for (int x = BASE; x <= BASE + 1; x++) {
                for (int z = BASE; z <= BASE + 1; z++) {
                    for (int y = 200; y <= 201; y++) {
                        world.setBlock(x, y, z, AsteroidBlocks.blockMinerBase, 0, 3);
                    }
                }
            }
        }
        if (ticks == 10) {
            // Let Galacticraft form the dock through its normal tile ticks first.
            require(world.getBlock(BASE, 200, BASE) == AsteroidBlocks.minerBaseFull, "Dock did not form");
            require(
                EntityAstroMiner.spawnMinerAtBase(
                    world,
                    BASE + 1,
                    201,
                    BASE + 1,
                    2,
                    new BlockVec3(BASE, 200, BASE),
                    FakePlayerFactory.get(world, new GameProfile(UUID.randomUUID(), "AMCLTest"))),
                "Miner creation failed");
            miner = findMiner(world, null);
            require(miner != null, "Spawned miner not found");
        }
        if (ticks == 15) {
            verifyWindow(world, miner);
            require(miner.playerMP == null, "Offline-owner behavior changed");
            // Exercise a jump beyond the old window; the pre-world hook must load the new window.
            miner.setPosition(BASE + 161, 200, BASE + 1);
        }
        if (ticks == 25) {
            Ticket ticket = verifyWindow(world, miner);
            require(
                ticket.getChunkList()
                    .size() == 13,
                "Dock intersection was not fully retained");
            Files.write(
                Paths.get("expected-miner.txt"),
                miner.getUniqueID()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8));
            pass("seed");
        }
    }

    private void restore(WorldServer world) throws Exception {
        if (ticks == 5) {
            UUID id = UUID
                .fromString(new String(Files.readAllBytes(Paths.get("expected-miner.txt")), StandardCharsets.UTF_8));
            miner = findMiner(world, id);
            require(miner != null, "Entity-bound ticket did not reload the miner after restart");
            verifyWindow(world, miner);
            miner.setDead();
        }
        if (ticks == 10) {
            for (Ticket ticket : ForgeChunkManager.getPersistentChunksFor(world)
                .values()) {
                require(ticket.getEntity() != miner, "Dead miner's ticket is still holding chunks");
            }
            pass("restore");
        }
    }

    private static EntityAstroMiner findMiner(WorldServer world, UUID id) {
        for (Object object : world.loadedEntityList) {
            if (object instanceof EntityAstroMiner) {
                EntityAstroMiner entity = (EntityAstroMiner) object;
                if (id == null || id.equals(entity.getUniqueID())) {
                    return entity;
                }
            }
        }
        return null;
    }

    private static Ticket verifyWindow(WorldServer world, EntityAstroMiner miner) {
        Set<Ticket> tickets = new HashSet<>();
        for (Ticket ticket : ForgeChunkManager.getPersistentChunksFor(world)
            .values()) {
            Entity entity = ticket.getEntity();
            if (entity == miner) {
                tickets.add(ticket);
            }
        }
        require(tickets.size() == 1, "Expected exactly one ticket, found " + tickets.size());
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
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
