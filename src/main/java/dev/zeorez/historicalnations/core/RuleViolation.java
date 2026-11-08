package dev.zeorez.historicalnations.core;

public final class RuleViolation extends RuntimeException {
    RuleViolation(String key) {
        super(key);
    }
}
