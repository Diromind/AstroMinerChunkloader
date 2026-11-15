package io.github.diromind.astrominerchunkloader;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Ticket lifecycle, independent of Minecraft so restoration and cleanup can be tested directly. */
final class LeaseController<M, T> {

    interface Backend<M, T> {

        UUID id(M miner);

        boolean isLoaded(M miner);

        default boolean canRun(M miner) {
            return true;
        }

        default void beforeRelease(M miner) {}

        T request(M miner);

        void update(M miner, T ticket);

        void release(T ticket);

        void denied(M miner);
    }

    private final Backend<M, T> backend;
    private final Map<UUID, Lease<M, T>> active = new LinkedHashMap<>();
    private final Map<UUID, Pending<M>> pending = new LinkedHashMap<>();
    private long ticks;

    LeaseController(Backend<M, T> backend) {
        this.backend = backend;
    }

    void enqueue(M miner) {
        UUID id = backend.id(miner);
        if (!active.containsKey(id) && !pending.containsKey(id)) {
            pending.put(id, new Pending<>(miner));
        }
    }

    void restore(M miner, T ticket) {
        UUID id = backend.id(miner);
        Lease<M, T> existing = active.get(id);
        if (existing != null) {
            if (existing.ticket != ticket) {
                backend.release(ticket);
            }
            return;
        }
        active.put(id, new Lease<>(miner, ticket));
        pending.remove(id);
    }

    void tick() {
        ticks++;
        Iterator<Lease<M, T>> iterator = active.values()
            .iterator();
        while (iterator.hasNext()) {
            Lease<M, T> lease = iterator.next();
            if (!backend.isLoaded(lease.miner) || !backend.canRun(lease.miner)) {
                backend.beforeRelease(lease.miner);
                backend.release(lease.ticket);
                iterator.remove();
            } else {
                backend.update(lease.miner, lease.ticket);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        // Loading chunks can emit EntityJoinWorldEvent, adding more pending miners.
        for (Map.Entry<UUID, Pending<M>> entry : new ArrayList<>(pending.entrySet())) {
            Pending<M> candidate = entry.getValue();
            if (!backend.isLoaded(candidate.miner) || !backend.canRun(candidate.miner)) {
                backend.beforeRelease(candidate.miner);
                pending.remove(entry.getKey());
                continue;
            }
            if (active.containsKey(entry.getKey())) {
                pending.remove(entry.getKey());
                continue;
            }
            if (ticks < candidate.retryAt) {
                continue;
            }
            T ticket = backend.request(candidate.miner);
            if (ticket == null) {
                if (candidate.retryAt == 0) {
                    backend.denied(candidate.miner);
                }
                candidate.retryAt = ticks + 100;
                continue;
            }
            active.put(entry.getKey(), new Lease<>(candidate.miner, ticket));
            pending.remove(entry.getKey());
            backend.update(candidate.miner, ticket);
        }
    }

    void releaseMatching(Predicate<M> predicate) {
        Iterator<Lease<M, T>> leases = active.values()
            .iterator();
        while (leases.hasNext()) {
            Lease<M, T> lease = leases.next();
            if (predicate.test(lease.miner)) {
                backend.beforeRelease(lease.miner);
                backend.release(lease.ticket);
                leases.remove();
            }
        }
        Iterator<Pending<M>> waiting = pending.values()
            .iterator();
        while (waiting.hasNext()) {
            Pending<M> candidate = waiting.next();
            if (predicate.test(candidate.miner)) {
                backend.beforeRelease(candidate.miner);
                waiting.remove();
            }
        }
    }

    private static final class Lease<M, T> {

        final M miner;
        final T ticket;

        Lease(M miner, T ticket) {
            this.miner = miner;
            this.ticket = ticket;
        }
    }

    private static final class Pending<M> {

        final M miner;
        long retryAt;

        Pending(M miner) {
            this.miner = miner;
        }
    }
}
