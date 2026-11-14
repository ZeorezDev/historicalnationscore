package dev.zeorez.historicalnations.core;

import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.EconomyApi;
import net.minecraft.nbt.*;
import java.math.BigInteger;
import java.util.*;
import static java.math.BigInteger.*;
import static dev.zeorez.historicalnations.core.WorldRegistry.require;

/** All ratios are integer basis points. Rates and the price index use six decimal places. */
final class MonetaryPolicy {
    static final long UNIT = 1_000_000, MAX_QUOTE = 1_000_000_000_000L;
    static final String REF = EconomyApi.REFERENCE;
    final UUID nation;
    String code = "", name = "", symbol = "";
    long quote = UNIT, index = UNIT, inflation, interest, issuedDay = Long.MIN_VALUE, issued;
    BigInteger opening = ZERO, volume = ZERO, demand = ZERO;
    final Deque<String> history = new ArrayDeque<>();

    MonetaryPolicy(UUID nation) { this.nation = nation; }
    void policy(long basisPoints) {
        require(basisPoints >= 0 && basisPoints <= 10000, "policy_invalid");
        interest = basisPoints;
    }
    void checkIssue(long day, long amount) {
        long used = day > issuedDay ? 0 : issued;
        require(day >= issuedDay && amount > 0 && used <= TerritoryConfig.ISSUE_LIMIT.get()
                && amount <= TerritoryConfig.ISSUE_LIMIT.get() - used, "issue_limit");
    }
    void issued(long day, long amount) {
        issued = day > issuedDay ? amount : issued + amount;
        issuedDay = day;
    }
    private static long boundedRatio(BigInteger numerator, BigInteger denominator, int limit) {
        var bound = valueOf(limit);
        return numerator.multiply(valueOf(10000)).divide(denominator.max(ONE)).min(bound).max(bound.negate()).longValueExact();
    }
    private static long scale(long base, long bps, long ceiling) {
        return valueOf(base).multiply(valueOf(10000 + bps)).divide(valueOf(10000)).max(ONE).min(valueOf(ceiling)).longValueExact();
    }
    void prices(BigInteger supply) {
        var activityUnit = valueOf(TerritoryConfig.ACTIVITY.get());
        long growth = boundedRatio(supply.subtract(opening), opening.max(activityUnit), 4000);
        long activity = boundedRatio(volume, supply.max(activityUnit), 2000);
        inflation = Math.max(-500, Math.min(500, growth / 8 - activity / 40 - interest / 50));
        index = scale(index, inflation, MAX_QUOTE);
        opening = supply;
    }
    void reprice(long day, long referenceInflation) {
        if (!REF.equals(code)) {
            long pressure = boundedRatio(demand, opening.max(valueOf(TerritoryConfig.ACTIVITY.get())), 4000) / 8;
            long cap = TerritoryConfig.FX_LIMIT.get();
            quote = scale(quote, Math.max(-cap, Math.min(cap, referenceInflation - inflation + pressure)), MAX_QUOTE);
        }
        if (history.size() == 32) history.removeFirst();
        history.addLast(java.time.LocalDate.ofEpochDay(day) + "  " + decimal(quote) + " " + REF);
        volume = ZERO;
        demand = ZERO;
    }
    static String decimal(long units) { return java.math.BigDecimal.valueOf(units, 6).stripTrailingZeros().toPlainString(); }
    static long indexed(long amount, long index, long anchor) {
        return valueOf(amount).multiply(valueOf(index)).divide(valueOf(anchor)).min(valueOf(Long.MAX_VALUE)).longValueExact();
    }
    CompoundTag save() {
        var t = new CompoundTag();
        if (nation != null) t.putUUID("Nation", nation);
        t.putString("Code", code); t.putString("Name", name); t.putString("Symbol", symbol);
        t.putLong("Quote", quote); t.putLong("Index", index); t.putLong("Inflation", inflation);
        t.putLong("Interest", interest); t.putLong("IssueDay", issuedDay); t.putLong("Issued", issued);
        t.putString("Opening", opening.toString()); t.putString("Volume", volume.toString()); t.putString("Demand", demand.toString());
        var list = new ListTag();
        history.forEach(line -> list.add(StringTag.valueOf(line)));
        t.put("History", list);
        return t;
    }
    static MonetaryPolicy read(CompoundTag t) {
        var m = new MonetaryPolicy(t.hasUUID("Nation") ? t.getUUID("Nation") : null);
        m.code = t.getString("Code"); m.name = t.getString("Name"); m.symbol = t.getString("Symbol");
        m.quote = number(t,"Quote",1,MAX_QUOTE); m.index = number(t,"Index",1,MAX_QUOTE);
        m.inflation = number(t,"Inflation",-500,500); m.interest = number(t,"Interest",0,10000);
        m.issuedDay = number(t,"IssueDay",Long.MIN_VALUE,Long.MAX_VALUE); m.issued = number(t,"Issued",0,Long.MAX_VALUE);
        m.opening = new BigInteger(t.getString("Opening")); m.volume = new BigInteger(t.getString("Volume"));
        m.demand = new BigInteger(t.getString("Demand"));
        if (m.volume.signum()<0 || m.opening.signum()<0 || !(t.get("History") instanceof ListTag history)
                || history.size()>32) throw new IllegalArgumentException("monetary data");
        history.forEach(entry -> m.history.add(entry.getAsString()));
        return m;
    }
    static long number(CompoundTag tag, String key, long min, long max) {
        long value = tag.getLong(key);
        if (!tag.contains(key, Tag.TAG_LONG) || value < min || value > max) throw new IllegalArgumentException(key);
        return value;
    }
}
