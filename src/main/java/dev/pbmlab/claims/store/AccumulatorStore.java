package dev.pbmlab.claims.store;

import dev.pbmlab.claims.domain.Accumulator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accumulators with optimistic locking. In SQL this is:
 * UPDATE accumulator SET ..., version = version + 1 WHERE member_id = ? AND plan_year = ? AND version = ?
 * and "0 rows updated" means someone else changed it first.
 */
public class AccumulatorStore {

    private final Map<String, Accumulator> rows = new ConcurrentHashMap<>();

    private static String key(String memberId, int planYear) {
        return memberId + "|" + planYear;
    }

    public Accumulator get(String memberId, int planYear) {
        return rows.getOrDefault(key(memberId, planYear), Accumulator.empty(memberId, planYear));
    }

    /** Compare-and-set: only writes if the stored version still equals expectedVersion. */
    public synchronized boolean compareAndSet(Accumulator updated, long expectedVersion) {
        Accumulator current = get(updated.memberId(), updated.planYear());
        if (current.version() != expectedVersion) {
            return false;
        }
        rows.put(key(updated.memberId(), updated.planYear()), updated);
        return true;
    }
}
