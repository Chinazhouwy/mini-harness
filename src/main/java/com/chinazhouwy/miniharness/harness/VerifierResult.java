package com.chinazhouwy.miniharness.harness;

import java.util.List;
import java.util.Objects;

public record VerifierResult(
        Verdict verdict,
        String reason,
        List<String> evidence
) {

    public VerifierResult {
        verdict = Objects.requireNonNull(verdict, "verdict");
        reason = Objects.requireNonNullElse(reason, "");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    public static VerifierResult pass(String reason, List<String> evidence) {
        return new VerifierResult(Verdict.PASS, reason, evidence);
    }

    public static VerifierResult retry(String reason, List<String> evidence) {
        return new VerifierResult(Verdict.RETRY, reason, evidence);
    }

    public static VerifierResult block(String reason, List<String> evidence) {
        return new VerifierResult(Verdict.BLOCK, reason, evidence);
    }
}
