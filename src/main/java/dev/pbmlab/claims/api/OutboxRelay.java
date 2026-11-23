package dev.pbmlab.claims.api;

import dev.pbmlab.claims.domain.OutboxEvent;
import dev.pbmlab.claims.store.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox relay: picks up unpublished events and "publishes" them.
 * Here it logs; in production this would send to Kafka (topic partitioned by memberId),
 * and only mark the event published after the broker acknowledges it.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxStore outbox;

    public OutboxRelay(OutboxStore outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelay = 2000)
    public void relay() {
        for (OutboxEvent e : outbox.unpublished()) {
            log.info("PUBLISH topic=claims-events key={} type={} payload={}", e.memberId(), e.type(), e.payload());
            outbox.markPublished(e.sequence());
        }
    }
}
