package dev.pbmlab.claims.store;

import dev.pbmlab.claims.domain.Accumulator;
import dev.pbmlab.claims.domain.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class StoresTest {

    @Test
    void accumulatorCompareAndSetRejectsStaleVersion() {
        AccumulatorStore store = new AccumulatorStore();
        Accumulator v0 = store.get("M", 2026);
        assertEquals(0, v0.version());

        Accumulator v1 = v0.add(new BigDecimal("10.00"), new BigDecimal("10.00"));
        assertTrue(store.compareAndSet(v1, 0));
        // A second writer that also read version 0 must lose.
        assertFalse(store.compareAndSet(v0.add(BigDecimal.ONE, BigDecimal.ONE), 0));
        assertEquals(new BigDecimal("10.00"), store.get("M", 2026).outOfPocketMet());
        assertEquals(1, store.get("M", 2026).version());
    }

    @Test
    void accumulatorsAreKeyedByMemberAndYear() {
        AccumulatorStore store = new AccumulatorStore();
        store.compareAndSet(store.get("M", 2026).add(BigDecimal.TEN, BigDecimal.TEN), 0);
        assertEquals(0, store.get("M", 2027).version());
        assertEquals(0, store.get("OTHER", 2026).version());
    }

    @Test
    void paidKeyIsUniqueUntilReleased() {
        ClaimStore store = new ClaimStore();
        assertTrue(store.registerPaidKey("K", "c1"));
        assertFalse(store.registerPaidKey("K", "c2"));
        store.releasePaidKey("K");
        assertTrue(store.registerPaidKey("K", "c3"));
    }

    @Test
    void outboxTracksPublishedState() {
        OutboxStore outbox = new OutboxStore();
        OutboxEvent a = outbox.append("ClaimPaid", "c1", "M", "{}");
        OutboxEvent b = outbox.append("ClaimRejected", "c2", "M", "{}");
        assertEquals(a.sequence() + 1, b.sequence());
        assertEquals(2, outbox.unpublished().size());

        outbox.markPublished(a.sequence());
        assertEquals(1, outbox.unpublished().size());
        assertEquals(b.sequence(), outbox.unpublished().get(0).sequence());
        assertEquals(2, outbox.all().size());
    }
}
