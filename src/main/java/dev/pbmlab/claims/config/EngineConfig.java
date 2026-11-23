package dev.pbmlab.claims.config;

import dev.pbmlab.claims.engine.AdjudicationEngine;
import dev.pbmlab.claims.store.AccumulatorStore;
import dev.pbmlab.claims.store.ClaimStore;
import dev.pbmlab.claims.store.OutboxStore;
import dev.pbmlab.claims.store.ReferenceData;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the plain-Java engine into Spring. Swap these beans for DB-backed ones later. */
@Configuration
public class EngineConfig {

    @Bean ReferenceData referenceData() { return ReferenceData.seeded(); }
    @Bean ClaimStore claimStore() { return new ClaimStore(); }
    @Bean AccumulatorStore accumulatorStore() { return new AccumulatorStore(); }
    @Bean OutboxStore outboxStore() { return new OutboxStore(); }
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean
    AdjudicationEngine adjudicationEngine(ReferenceData ref, ClaimStore claims, AccumulatorStore acc,
                                          OutboxStore outbox, Clock clock) {
        return new AdjudicationEngine(ref, claims, acc, outbox, clock);
    }
}
