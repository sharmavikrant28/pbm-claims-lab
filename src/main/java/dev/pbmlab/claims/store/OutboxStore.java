package dev.pbmlab.claims.store;

import dev.pbmlab.claims.domain.OutboxEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transactional outbox. Events are written in the same unit of work as the claim,
 * then a relay publishes them (here: logs them; in production: Kafka).
 */
public class OutboxStore {

    private final List<OutboxEvent> events = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public synchronized OutboxEvent append(String type, String claimId, String memberId, String payload) {
        OutboxEvent e = new OutboxEvent(sequence.incrementAndGet(), type, claimId, memberId, payload, Instant.now(), false);
        events.add(e);
        return e;
    }

    public synchronized List<OutboxEvent> unpublished() {
        return events.stream().filter(e -> !e.published()).toList();
    }

    public synchronized void markPublished(long seq) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).sequence() == seq) {
                events.set(i, events.get(i).markPublished());
            }
        }
    }

    public synchronized List<OutboxEvent> all() {
        return List.copyOf(events);
    }
}
