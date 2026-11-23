package dev.pbmlab.claims.domain;

/** A small subset of NCPDP reject codes used in this lab. */
public enum RejectCode {
    NON_MATCHED_PHARMACY("50", "Non-matched pharmacy number"),
    PATIENT_NOT_COVERED("65", "Patient is not covered"),
    PRODUCT_NOT_COVERED("70", "Product/service not covered"),
    PRIOR_AUTH_REQUIRED("75", "Prior authorization required"),
    PLAN_LIMITATIONS_EXCEEDED("76", "Plan limitations exceeded");

    private final String code;
    private final String message;

    RejectCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() { return code; }
    public String message() { return message; }
}
