package dev.pbmlab.claims.store;

import dev.pbmlab.claims.domain.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Reference data the claims path reads: members, plans, formulary, prices, pharmacies, prior auths.
 * In a real PBM each of these is owned by its own domain service and pushed into a read cache.
 * Here it is seeded in memory with fictional data so the lab runs with no database.
 */
public class ReferenceData {

    private final Map<String, Member> members = new HashMap<>();
    private final Map<String, Plan> plans = new HashMap<>();
    private final Map<String, FormularyEntry> formulary = new HashMap<>();
    private final Map<String, DrugPrice> prices = new HashMap<>();
    private final Map<String, Pharmacy> pharmacies = new HashMap<>();
    private final List<PriorAuth> priorAuths = new ArrayList<>();

    public static ReferenceData seeded() {
        ReferenceData r = new ReferenceData();

        r.plans.put("PLAN-GOLD", new Plan("PLAN-GOLD", money("100.00"), money("1000.00"),
                Map.of(1, money("10.00"), 2, money("40.00"), 3, money("80.00"), 4, money("250.00"))));

        r.members.put("M1001", new Member("M1001", "Asha Rao", "PLAN-GOLD",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31)));
        r.members.put("M1002", new Member("M1002", "Ravi Mehta", "PLAN-GOLD",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))); // terminated

        r.pharmacies.put("PH-001", new Pharmacy("PH-001", "Main Street Pharmacy", true,
                new BigDecimal("0.15"), money("1.50")));
        r.pharmacies.put("PH-999", new Pharmacy("PH-999", "Out-of-network Pharmacy", false,
                BigDecimal.ZERO, BigDecimal.ZERO));

        // Fictional NDCs and drugs.
        r.add(new FormularyEntry("11111-2222-01", "Atorvastatin 20 mg tablet (generic)", 1, true, false, 30),
                new DrugPrice("11111-2222-01", new BigDecimal("1.20"), new BigDecimal("0.10")));
        r.add(new FormularyEntry("22222-3333-01", "BrandStatin 10 mg tablet (brand)", 2, false, false, 30),
                new DrugPrice("22222-3333-01", new BigDecimal("12.00"), null));
        r.add(new FormularyEntry("33333-4444-01", "BioInject 40 mg pen (specialty)", 4, false, true, 2),
                new DrugPrice("33333-4444-01", new BigDecimal("3000.00"), null));
        // NDC 99999-0000-01 is deliberately NOT on the formulary.

        r.priorAuths.add(new PriorAuth("M1001", "33333-4444-01",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 6, 30)));
        return r;
    }

    private void add(FormularyEntry f, DrugPrice p) {
        formulary.put(f.ndc(), f);
        prices.put(p.ndc(), p);
    }

    private static BigDecimal money(String s) {
        return new BigDecimal(s);
    }

    public Optional<Member> member(String id) { return Optional.ofNullable(members.get(id)); }
    public Optional<Plan> plan(String id) { return Optional.ofNullable(plans.get(id)); }
    public Optional<FormularyEntry> formulary(String ndc) { return Optional.ofNullable(formulary.get(ndc)); }
    public Optional<DrugPrice> price(String ndc) { return Optional.ofNullable(prices.get(ndc)); }
    public Optional<Pharmacy> pharmacy(String id) { return Optional.ofNullable(pharmacies.get(id)); }

    public boolean hasPriorAuth(String memberId, String ndc, LocalDate date) {
        return priorAuths.stream().anyMatch(pa -> pa.covers(memberId, ndc, date));
    }

    /** Snapshot for the /api/reference endpoint, so you can see the seeded data. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plans", plans.values());
        m.put("members", members.values());
        m.put("pharmacies", pharmacies.values());
        m.put("formulary", formulary.values());
        m.put("prices", prices.values());
        m.put("priorAuths", priorAuths);
        return m;
    }
}
