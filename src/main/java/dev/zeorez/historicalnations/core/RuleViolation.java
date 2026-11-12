package dev.zeorez.historicalnations.core;

public final class RuleViolation extends RuntimeException {
    public RuleViolation(String key) {
        super(key);
    }
}
