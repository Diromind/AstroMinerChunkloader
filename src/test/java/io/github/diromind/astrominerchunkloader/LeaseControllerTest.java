package io.github.diromind.astrominerchunkloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

public class LeaseControllerTest {

    private final FakeBackend backend = new FakeBackend();
    private final LeaseController<Miner, Object> controller = new LeaseController<>(backend);

    @Test
    public void joinEventDefersAllAllocationUntilTick() {
        Miner miner = new Miner();
        controller.enqueue(miner);
        assertEquals(0, backend.requests);
        controller.tick();
        assertEquals(1, backend.requests);
        assertEquals(1, backend.updates.size());
    }

    @Test
    public void restoredTicketWinsOverJoinEventWithoutAllocatingDuplicate() {
        Miner miner = new Miner();
        Object restored = new Object();
        controller.enqueue(miner);
        controller.restore(miner, restored);
        controller.tick();
        assertEquals(0, backend.requests);
        assertEquals(restored, backend.updates.get(0));
        assertTrue(backend.released.isEmpty());
    }

    @Test
    public void repeatedJoinsDoNotMultiplyTickets() {
        Miner miner = new Miner();
        controller.enqueue(miner);
        controller.enqueue(miner);
        controller.tick();
        controller.enqueue(miner);
        controller.tick();
        assertEquals(1, backend.requests);
        assertEquals(2, backend.updates.size());
    }

    @Test
    public void duplicateRestoredTicketIsReleasedButSameTicketIsNot() {
        Miner miner = new Miner();
        Object original = new Object();
        Object duplicate = new Object();
        controller.restore(miner, original);
        controller.restore(miner, original);
        controller.restore(miner, duplicate);
        assertEquals(1, backend.released.size());
        assertEquals(duplicate, backend.released.get(0));
    }

    @Test
    public void deadOrRemovedMinerReleasesItsTicketOnce() {
        Miner miner = new Miner();
        Object ticket = new Object();
        controller.restore(miner, ticket);
        miner.loaded = false;
        controller.tick();
        controller.tick();
        assertEquals(1, backend.released.size());
        assertEquals(ticket, backend.released.get(0));
        assertTrue(backend.updates.isEmpty());
    }

    @Test
    public void canceledSpawnNeverReceivesTicket() {
        Miner miner = new Miner();
        controller.enqueue(miner);
        miner.loaded = false;
        controller.tick();
        assertEquals(0, backend.requests);
    }

    @Test
    public void deniedRequestRetriesAfter100TicksAndWarnsOnce() {
        Miner miner = new Miner();
        backend.exhausted = true;
        controller.enqueue(miner);
        controller.tick();
        for (int tick = 0; tick < 99; tick++) {
            controller.tick();
        }
        assertEquals(1, backend.requests);
        backend.exhausted = false;
        controller.tick();
        assertEquals(2, backend.requests);
        assertEquals(1, backend.warnings);
        assertEquals(1, backend.updates.size());
    }

    @Test
    public void loadingAnotherMinerDuringUpdateDoesNotMutateIteration() {
        Miner first = new Miner();
        Miner second = new Miner();
        controller.restore(first, new Object());
        backend.duringUpdate = () -> controller.enqueue(second);
        controller.tick();
        assertEquals(1, backend.requests);
        assertEquals(2, backend.updates.size());
    }

    @Test
    public void offlineOwnerNeverReceivesTicket() {
        Miner miner = new Miner();
        miner.canRun = false;
        controller.enqueue(miner);
        controller.tick();
        controller.tick();
        assertEquals(0, backend.requests);
        assertTrue(backend.updates.isEmpty());
    }

    @Test
    public void logoutReleasesImmediatelyAndPendingJoinCannotReallocate() {
        Miner miner = new Miner();
        Object ticket = new Object();
        controller.restore(miner, ticket);
        controller.enqueue(miner);
        miner.canRun = false;
        controller.releaseMatching(candidate -> candidate == miner);
        assertEquals(1, backend.released.size());
        controller.tick();
        assertEquals(0, backend.requests);
        assertEquals(1, backend.released.size());
    }

    @Test
    public void offlineRestoredTicketIsReleasedWithoutUpdatingChunks() {
        Miner miner = new Miner();
        miner.canRun = false;
        controller.restore(miner, new Object());
        controller.tick();
        assertEquals(1, backend.released.size());
        assertTrue(backend.updates.isEmpty());
    }

    @Test
    public void loginCanAcquireANewTicketAfterLogout() {
        Miner miner = new Miner();
        controller.enqueue(miner);
        controller.tick();
        miner.canRun = false;
        controller.releaseMatching(candidate -> true);
        miner.canRun = true;
        controller.enqueue(miner);
        controller.tick();
        assertEquals(2, backend.requests);
        assertEquals(1, backend.released.size());
    }

    @Test
    public void logoutOnlyReleasesMatchingMiners() {
        Miner first = new Miner();
        Miner second = new Miner();
        Object firstTicket = new Object();
        Object secondTicket = new Object();
        controller.restore(first, firstTicket);
        controller.restore(second, secondTicket);
        controller.releaseMatching(candidate -> candidate == first);
        controller.tick();
        assertEquals(1, backend.released.size());
        assertEquals(firstTicket, backend.released.get(0));
        assertEquals(secondTicket, backend.updates.get(0));
    }

    private static final class Miner {

        final UUID id = UUID.randomUUID();
        boolean loaded = true;
        boolean canRun = true;
    }

    private static final class FakeBackend implements LeaseController.Backend<Miner, Object> {

        final List<Object> updates = new ArrayList<>();
        final List<Object> released = new ArrayList<>();
        int requests;
        int warnings;
        boolean exhausted;
        Runnable duringUpdate;

        @Override
        public UUID id(Miner miner) {
            return miner.id;
        }

        @Override
        public boolean isLoaded(Miner miner) {
            return miner.loaded;
        }

        @Override
        public boolean canRun(Miner miner) {
            return miner.canRun;
        }

        @Override
        public Object request(Miner miner) {
            requests++;
            return exhausted ? null : new Object();
        }

        @Override
        public void update(Miner miner, Object ticket) {
            updates.add(ticket);
            if (duringUpdate != null) {
                Runnable action = duringUpdate;
                duringUpdate = null;
                action.run();
            }
        }

        @Override
        public void release(Object ticket) {
            released.add(ticket);
        }

        @Override
        public void denied(Miner miner) {
            warnings++;
        }
    }
}
