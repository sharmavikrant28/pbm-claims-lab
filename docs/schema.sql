-- Next step: PostgreSQL schema for the stores (not used by the in-memory version).
CREATE TABLE claim (
    claim_id           UUID PRIMARY KEY,
    idempotency_key    TEXT        NOT NULL,
    member_id          TEXT        NOT NULL,
    ndc                TEXT        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('PAID','REJECTED','REVERSED')),
    reject_code        TEXT,
    allowed_amount     NUMERIC(12,2) NOT NULL,
    patient_pay        NUMERIC(12,2) NOT NULL,
    plan_pay           NUMERIC(12,2) NOT NULL,
    deductible_applied NUMERIC(12,2) NOT NULL,
    plan_year          INT         NOT NULL,
    request            JSONB       NOT NULL,
    trace              JSONB       NOT NULL,
    adjudicated_at     TIMESTAMPTZ NOT NULL
);
-- Only one PAID claim per key: this is what stops double payment.
CREATE UNIQUE INDEX ux_claim_paid_key ON claim (idempotency_key) WHERE status = 'PAID';

CREATE TABLE accumulator (
    member_id        TEXT          NOT NULL,
    plan_year        INT           NOT NULL,
    deductible_met   NUMERIC(12,2) NOT NULL DEFAULT 0,
    out_of_pocket_met NUMERIC(12,2) NOT NULL DEFAULT 0,
    version          BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (member_id, plan_year)
);
-- Optimistic update: 0 rows updated means someone else changed it first, so retry.
-- UPDATE accumulator SET deductible_met = ?, out_of_pocket_met = ?, version = version + 1
--  WHERE member_id = ? AND plan_year = ? AND version = ?;

CREATE TABLE outbox_event (
    sequence    BIGSERIAL PRIMARY KEY,
    type        TEXT        NOT NULL,
    claim_id    UUID        NOT NULL,
    member_id   TEXT        NOT NULL,
    payload     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published   BOOLEAN     NOT NULL DEFAULT false
);
