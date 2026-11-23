package dev.pbmlab.claims.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests of the REST API: boots the full application on a random port and talks HTTP/JSON.
 * State is in memory, so each test gets a fresh context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ClaimsApiIT {

    @Autowired
    TestRestTemplate http;

    private static String claimJson(String pharmacy, String rx, String member, String ndc, int qty, int days,
                                    String dos, String uc) {
        return """
                {"pharmacyId":"%s","rxNumber":"%s","fillNumber":0,"memberId":"%s","ndc":"%s",
                 "quantity":%d,"daysSupply":%d,"dateOfService":"%s","usualAndCustomary":%s}
                """.formatted(pharmacy, rx, member, ndc, qty, days, dos, uc);
    }

    private static String claimJson(String rx, String ndc, int qty, int days, String uc) {
        return claimJson("PH-001", rx, "M1001", ndc, qty, days, "2026-12-01", uc);
    }

    private ResponseEntity<JsonNode> post(String path, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(path, new HttpEntity<>(json, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return http.getForEntity(path, JsonNode.class);
    }

    private static String money(JsonNode n, String field) {
        return n.get(field).decimalValue().setScale(2).toPlainString();
    }

    @Test
    void referenceDataIsExposed() {
        ResponseEntity<JsonNode> r = get("/api/reference");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        JsonNode body = r.getBody();
        for (String key : new String[]{"plans", "members", "pharmacies", "formulary", "prices", "priorAuths"}) {
            assertTrue(body.has(key), "missing " + key);
        }
        assertEquals(2, body.get("members").size());
        assertEquals(3, body.get("formulary").size());
    }

    @Test
    void genericClaimPaysAndRetryIsDuplicate() {
        String json = claimJson("RX1001", "11111-2222-01", 30, 30, "12.00");
        ResponseEntity<JsonNode> first = post("/api/claims", json);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        JsonNode b = first.getBody();
        assertEquals("PAID", b.get("status").asText());
        assertEquals("4.50", money(b, "allowedAmount"));
        assertEquals("4.50", money(b, "patientPay"));
        assertEquals("0.00", money(b, "planPay"));
        assertFalse(b.get("duplicate").asBoolean());
        assertTrue(b.get("trace").size() >= 7);

        JsonNode retry = post("/api/claims", json).getBody();
        assertTrue(retry.get("duplicate").asBoolean());
        assertEquals(b.get("claimId").asText(), retry.get("claimId").asText());

        JsonNode acc = get("/api/members/M1001/accumulators/2026").getBody();
        assertEquals("4.50", money(acc, "outOfPocketMet"));
        assertEquals("4.50", money(acc, "deductibleMet"));
        assertEquals(1, acc.get("version").asLong());
    }

    @Test
    void brandAndSpecialtyClaimsPriceAndShareCostCorrectly() {
        JsonNode brand = post("/api/claims", claimJson("RX1002", "22222-3333-01", 30, 30, "400.00")).getBody();
        assertEquals("PAID", brand.get("status").asText());
        assertEquals("307.50", money(brand, "allowedAmount"));
        assertEquals("140.00", money(brand, "patientPay"));
        assertEquals("167.50", money(brand, "planPay"));

        JsonNode specialty = post("/api/claims", claimJson("RX1003", "33333-4444-01", 2, 28, "9000.00")).getBody();
        assertEquals("PAID", specialty.get("status").asText());
        assertEquals("5101.50", money(specialty, "allowedAmount"));
        assertEquals("250.00", money(specialty, "patientPay"));
        assertEquals("4851.50", money(specialty, "planPay"));

        JsonNode acc = get("/api/members/M1001/accumulators/2026").getBody();
        assertEquals("100.00", money(acc, "deductibleMet"));
        assertEquals("390.00", money(acc, "outOfPocketMet"));
    }

    @Test
    void rejectCodesAreReturnedForEachEdit() {
        assertReject(claimJson("PH-999", "RX1", "M1001", "11111-2222-01", 30, 30, "2026-12-01", "12.00"), "50");
        assertReject(claimJson("PH-001", "RX2", "M1002", "11111-2222-01", 30, 30, "2026-12-01", "12.00"), "65");
        assertReject(claimJson("RX3", "99999-0000-01", 30, 30, "12.00"), "70");
        assertReject(claimJson("RX4", "11111-2222-01", 90, 30, "12.00"), "76");
        assertReject(claimJson("PH-001", "RX5", "M1001", "33333-4444-01", 2, 28, "2027-07-01", "9000.00"), "75");

        // Rejections never touch accumulators
        JsonNode acc = get("/api/members/M1001/accumulators/2026").getBody();
        assertEquals(0, acc.get("version").asLong());
    }

    private void assertReject(String json, String code) {
        ResponseEntity<JsonNode> r = post("/api/claims", json);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("REJECTED", r.getBody().get("status").asText());
        assertEquals(code, r.getBody().get("rejectCode").asText());
        assertFalse(r.getBody().get("rejectMessage").asText().isBlank());
        assertEquals("0.00", money(r.getBody(), "planPay"));
    }

    @Test
    void getClaimByIdAndNotFound() {
        JsonNode paid = post("/api/claims", claimJson("RX1", "11111-2222-01", 30, 30, "12.00")).getBody();
        String id = paid.get("claimId").asText();

        ResponseEntity<JsonNode> found = get("/api/claims/" + id);
        assertEquals(HttpStatus.OK, found.getStatusCode());
        assertEquals(id, found.getBody().get("claimId").asText());
        assertEquals("PAID", found.getBody().get("status").asText());

        assertEquals(HttpStatus.NOT_FOUND, get("/api/claims/does-not-exist").getStatusCode());
    }

    @Test
    void reversalFlow() {
        String json = claimJson("RX1002", "22222-3333-01", 30, 30, "400.00");
        String id = post("/api/claims", json).getBody().get("claimId").asText();

        ResponseEntity<JsonNode> rev = post("/api/claims/" + id + "/reversal", "");
        assertEquals(HttpStatus.OK, rev.getStatusCode());
        assertEquals("REVERSED", rev.getBody().get("status").asText());
        assertEquals("REVERSED", get("/api/claims/" + id).getBody().get("status").asText());

        JsonNode acc = get("/api/members/M1001/accumulators/2026").getBody();
        assertEquals("0.00", money(acc, "outOfPocketMet"));
        assertEquals("0.00", money(acc, "deductibleMet"));

        // Reversing again -> 409 Conflict
        ResponseEntity<JsonNode> again = post("/api/claims/" + id + "/reversal", "");
        assertEquals(HttpStatus.CONFLICT, again.getStatusCode());
        assertTrue(again.getBody().get("error").asText().contains("REVERSED"));

        // Same fill can be billed again as a new claim
        JsonNode rebill = post("/api/claims", json).getBody();
        assertEquals("PAID", rebill.get("status").asText());
        assertFalse(rebill.get("duplicate").asBoolean());
        assertNotEquals(id, rebill.get("claimId").asText());
    }

    @Test
    void reversingRejectedClaimIsConflict() {
        String id = post("/api/claims", claimJson("RX3", "99999-0000-01", 30, 30, "12.00"))
                .getBody().get("claimId").asText();
        assertEquals(HttpStatus.CONFLICT, post("/api/claims/" + id + "/reversal", "").getStatusCode());
    }

    @Test
    void reversingUnknownClaimIsBadRequest() {
        ResponseEntity<JsonNode> r = post("/api/claims/no-such-claim/reversal", "");
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertTrue(r.getBody().get("error").asText().contains("no-such-claim"));
    }

    @Test
    void invalidRequestsAreBadRequest() {
        ResponseEntity<JsonNode> missing = post("/api/claims",
                "{\"pharmacyId\":\"PH-001\",\"rxNumber\":\"RX1\",\"memberId\":\"M1001\",\"quantity\":30,\"daysSupply\":30}");
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertTrue(missing.getBody().get("error").asText().contains("required"));

        ResponseEntity<JsonNode> zeroQty = post("/api/claims", claimJson("RX1", "11111-2222-01", 0, 30, "12.00"));
        assertEquals(HttpStatus.BAD_REQUEST, zeroQty.getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST, post("/api/claims", "{not json").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                post("/api/claims", claimJson("PH-001", "RX1", "M1001", "11111-2222-01", 30, 30, "2026-13-45", "1")).getStatusCode());
    }

    @Test
    void unknownMemberAccumulatorsAreEmpty() {
        JsonNode acc = get("/api/members/NOBODY/accumulators/2026").getBody();
        assertEquals("NOBODY", acc.get("memberId").asText());
        assertEquals("0.00", money(acc, "outOfPocketMet"));
        assertEquals(0, acc.get("version").asLong());
    }

    @Test
    void outboxRecordsEventsAndRelayPublishesThem() throws InterruptedException {
        String id = post("/api/claims", claimJson("RX1", "11111-2222-01", 30, 30, "12.00")).getBody().get("claimId").asText();
        post("/api/claims", claimJson("RX2", "99999-0000-01", 30, 30, "12.00"));
        post("/api/claims/" + id + "/reversal", "");

        JsonNode events = get("/api/outbox").getBody();
        assertEquals(3, events.size());
        assertEquals("ClaimPaid", events.get(0).get("type").asText());
        assertEquals("ClaimRejected", events.get(1).get("type").asText());
        assertEquals("ClaimReversed", events.get(2).get("type").asText());
        assertEquals("M1001", events.get(0).get("memberId").asText());

        // OutboxRelay runs every 2s; wait up to 10s for everything to be marked published.
        long deadline = System.currentTimeMillis() + 10_000;
        boolean allPublished = false;
        while (!allPublished && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            allPublished = true;
            for (JsonNode e : get("/api/outbox").getBody()) {
                allPublished &= e.get("published").asBoolean();
            }
        }
        assertTrue(allPublished, "relay did not publish outbox events");
    }

    @Test
    void openApiSpecAndSwaggerUiAreServed() {
        ResponseEntity<JsonNode> spec = get("/v3/api-docs");
        assertEquals(HttpStatus.OK, spec.getStatusCode());
        assertEquals("PBM Claims Lab API", spec.getBody().at("/info/title").asText());
        JsonNode paths = spec.getBody().get("paths");
        for (String p : new String[]{"/api/claims", "/api/claims/{claimId}", "/api/claims/{claimId}/reversal",
                "/api/members/{memberId}/accumulators/{planYear}", "/api/outbox", "/api/reference"}) {
            assertTrue(paths.has(p), "OpenAPI spec missing " + p);
        }
        assertTrue(spec.getBody().at("/paths/~1api~1claims/post/requestBody/content/application~1json/examples").size() >= 5);

        ResponseEntity<String> ui = http.getForEntity("/swagger-ui/index.html", String.class);
        assertEquals(HttpStatus.OK, ui.getStatusCode());
        assertTrue(ui.getBody().contains("swagger-ui"));
    }

    @Test
    void actuatorHealthIsUp() {
        ResponseEntity<JsonNode> r = get("/actuator/health");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("UP", r.getBody().get("status").asText());
    }
}
