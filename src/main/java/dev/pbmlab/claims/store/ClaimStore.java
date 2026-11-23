package dev.pbmlab.claims.store;

import dev.pbmlab.claims.domain.Claim;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores claims by id, and paid claims by idempotency key.
 * In PostgreSQL the idempotency index would be a UNIQUE constraint on the key for PAID claims.
 */
public class ClaimStore {

    private final Map<String, Claim> byId = new ConcurrentHashMap<>();
    private final Map<String, String> paidByKey = new ConcurrentHashMap<>();

    public Optional<Claim> findPaidByKey(String key) {
        String id = paidByKey.get(key);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public Optional<Claim> findById(String claimId) {
        return Optional.ofNullable(byId.get(claimId));
    }

    public void save(Claim claim) {
        byId.put(claim.claimId(), claim);
    }

    /** Returns false if another paid claim already holds this key (the unique-constraint check). */
    public boolean registerPaidKey(String key, String claimId) {
        return paidByKey.putIfAbsent(key, claimId) == null;
    }

    /** After a reversal the same Rx and fill can be billed again. */
    public void releasePaidKey(String key) {
        paidByKey.remove(key);
    }

    public Collection<Claim> all() {
        return byId.values();
    }
}
