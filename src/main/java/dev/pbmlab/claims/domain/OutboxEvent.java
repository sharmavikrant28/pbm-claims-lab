package dev.pbmlab.claims.domain;

import java.time.Instant;

/** An event saved together with the claim (transactional outbox), published later by a relay. */
public record OutboxEvent(long sequence, String type, String claimId, String memberId,
                          String payload, Instant createdAt, boolean published) {

    public OutboxEvent markPublished() {
        return new OutboxEvent(sequence, type, claimId, memberId, payload, createdAt, true);
    }
}
