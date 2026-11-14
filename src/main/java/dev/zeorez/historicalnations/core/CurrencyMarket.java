package dev.zeorez.historicalnations.core;

import dev.zeorez.historicalnations.TerritoryConfig;
import net.minecraft.nbt.*;
import java.math.BigInteger;
import java.util.*;
import static dev.zeorez.historicalnations.core.Ledger.Account;
import static dev.zeorez.historicalnations.core.MonetaryPolicy.REF;
import static dev.zeorez.historicalnations.core.WorldRegistry.require;

final class CurrencyMarket {
    final Map<String, MonetaryPolicy> currencies = new TreeMap<>();
    final Map<UUID, MonetaryPolicy> banks = new LinkedHashMap<>();
    private final WorldRegistry registry;
    private final Ledger ledger;
    CurrencyMarket(WorldRegistry registry, Ledger ledger) {
        this.registry = registry;
        this.ledger = ledger;
        var ref = new MonetaryPolicy(null);
        ref.code = REF;
        currencies.put(REF,ref);
    }

    MonetaryPolicy bank(UUID nation) {
        ledger.available();
        require(registry.getNationById(nation).isPresent(), "unknown_nation");
        return banks.computeIfAbsent(nation, MonetaryPolicy::new);
    }
    MonetaryPolicy resolve(String code) {
        ledger.available();
        var currency = currencies.get(code.toUpperCase(Locale.ROOT));
        require(currency != null, "unknown_currency");
        return currency;
    }
    String official(UUID nation) {
        if (nation == null) return REF;
        var m = bank(nation);
        return m.code.isEmpty() ? REF : m.code;
    }
    void create(UUID nation, String name, String symbol, String input) {
        var m = bank(nation);
        String code = input.toUpperCase(Locale.ROOT);
        String clean = WorldRegistry.cleanName(name);
        require(m.code.isEmpty() && input.matches("[A-Za-z]{3,6}") && !currencies.containsKey(code)
                && symbol.matches("[\\p{L}\\p{Sc}]{1,6}") && !clean.equalsIgnoreCase(TerritoryConfig.CURRENCY.get())
                && currencies.values().stream().noneMatch(c -> c.name.equalsIgnoreCase(clean)), "currency_invalid");
        m.code = code; m.name = clean; m.symbol = symbol;
        currencies.put(code, m);
        registry.setDirty();
    }
    void issue(UUID leader, long amount, long day) {
        UUID nation = registry.ledNation(leader).id();
        var m = bank(nation);
        require(!m.code.isEmpty(), "unknown_currency");
        m.checkIssue(day,amount);
        ledger.post(null, Account.nation(nation).in(m.code), amount, leader.toString(), "central bank issue");
        m.issued(day,amount);
    }

    long exchange(UUID player, String source, String target, long amount) {
        var from = resolve(source);
        var to = resolve(target);
        require(from != to && amount > 0, "bad_amount");
        var result = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(from.quote)).divide(BigInteger.valueOf(to.quote));
        require(result.signum() > 0 && result.bitLength() <= 63, "fx_amount");
        long output = result.longValueExact();
        var payer = Account.player(player).in(from.code);
        var receiver = Account.player(player).in(to.code);
        var in = Account.reserve(from.code);
        var out = Account.reserve(to.code);
        require(ledger.balance(out) >= output, "fx_reserve");
        ledger.checkTransfer(payer,in,amount);
        ledger.checkTransfer(out,receiver,output);
        ledger.post(payer,in,amount,player.toString(),"exchange " + to.code);
        ledger.post(out,receiver,output,player.toString(),"exchange " + from.code);
        from.demand = from.demand.subtract(BigInteger.valueOf(amount));
        to.demand = to.demand.add(result);
        return output;
    }
    void trade(Account payer, long amount) {
        var policy = resolve(payer.currency());
        var units = BigInteger.valueOf(amount);
        policy.volume = policy.volume.add(units);
        if (REF.equals(policy.code)) registry.getPlayerNation(payer.id()).ifPresent(n -> {
            var national = bank(n.id());
            if (national.code.isEmpty()) national.volume = national.volume.add(units);
        });
    }
    void settle(long day) {
        var totals = ledger.supplies();
        for (var national : banks.values()) {
            if (national.code.isEmpty()) {
                national.prices(BigInteger.ZERO);
                national.volume = BigInteger.ZERO;
            }
        }
        for (var m : currencies.values()) m.prices(totals.getOrDefault(m.code, BigInteger.ZERO));
        long ref = currencies.get(REF).inflation;
        currencies.values().forEach(m -> m.reprice(day,ref));
    }
    ListTag save() {
        var entries = new ListTag();
        entries.add(currencies.get(REF).save());
        banks.values().forEach(m -> entries.add(m.save()));
        return entries;
    }
    void load(ListTag entries) {
        currencies.clear(); banks.clear();
        for (var entry : entries) {
            var m = MonetaryPolicy.read((CompoundTag)entry);
            if (m.nation != null && banks.putIfAbsent(m.nation,m) != null) throw new IllegalArgumentException("duplicate issuer");
            if ((m.nation == null) != m.code.equals(REF) || m.code.equals(REF) && m.quote != MonetaryPolicy.UNIT)
                throw new IllegalArgumentException("reference currency");
            if (!m.code.isEmpty() && (!m.code.matches("[A-Z]{3,6}") || currencies.putIfAbsent(m.code,m) != null))
                throw new IllegalArgumentException("currency code");
        }
        if (!currencies.containsKey(REF)) throw new IllegalArgumentException("missing reference");
    }
}
