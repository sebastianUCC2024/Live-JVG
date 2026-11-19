package com.live_jgv.archiver.domain;

/** Los registros inválidos se conservan en cuarentena para revisión. */
public record DecodedInput(SportEvent event, String canonicalPayload, Reason reason) {
    public enum Reason { MALFORMED_JSON, INVALID_EVENT, KEY_MISMATCH }
    public DecodedInput {
        boolean valid = event != null && canonicalPayload != null && reason == null;
        boolean invalid = event == null && canonicalPayload == null && reason != null;
        if (!valid && !invalid) throw new IllegalArgumentException("Invalid decoding result");
    }
    public static DecodedInput valid(SportEvent event, String payload) { return new DecodedInput(event, payload, null); }
    public static DecodedInput invalid(Reason reason) { return new DecodedInput(null, null, reason); }
}
