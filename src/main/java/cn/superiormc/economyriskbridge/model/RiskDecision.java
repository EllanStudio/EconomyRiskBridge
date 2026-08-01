package cn.superiormc.economyriskbridge.model;

import cn.superiormc.economyriskbridge.risk.RiskRule;

import java.util.List;
import java.util.stream.Collectors;

public record RiskDecision(Action action, int score, List<Reason> reasons) {

    public String reasonCodes() {
        return this.reasons.stream().map(reason -> reason.rule().code()).collect(Collectors.joining(","));
    }

    public String primaryCode() {
        return this.reasons.stream().filter(Reason::hard).findFirst()
            .or(() -> this.reasons.stream().max(java.util.Comparator.comparingInt(Reason::score)))
            .map(reason -> reason.rule().code()).orElse(RiskRule.AUDIT_UNAVAILABLE.code());
    }

    public enum Action {
        PASS,
        REVIEW,
        BLOCK
    }

    public record Reason(RiskRule rule, int score, boolean hard) {
    }
}
