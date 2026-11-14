package dev.zeorez.historicalnations.core;

import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.TerritoryConfig;
import net.minecraft.nbt.*;
import dev.zeorez.historicalnations.api.EconomyApi;
import java.math.BigInteger;
import java.util.*;
import static dev.zeorez.historicalnations.core.WorldRegistry.require;

/** Part of the registry snapshot, so a sale and its payment share a save boundary. */
final class Ledger {
    enum Kind { PLAYER, TOWN, NATION, RESERVE }
    record Account(Kind kind, UUID id, String currency) {
        Account(Kind kind, UUID id) { this(kind, id, EconomyApi.REFERENCE); }
        Account in(String code) { return new Account(kind, id, code); }
        static Account reserve(String code) { return new Account(Kind.RESERVE, new UUID(0, 0), code); }
        static Account player(UUID id) { return new Account(Kind.PLAYER, id); }
        static Account town(UUID id) { return new Account(Kind.TOWN, id); }
        static Account nation(UUID id) { return new Account(Kind.NATION, id); }
        @Override public String toString() { return kind + "/" + id + "/" + currency; }
    }
    static final int HISTORY_LIMIT = 32;
    static final class State {
        long balance, tax, upkeep;
        boolean initialized;
        String taxCode = EconomyApi.REFERENCE, upkeepCode = EconomyApi.REFERENCE;
        long taxIndex = MonetaryPolicy.UNIT, upkeepIndex = MonetaryPolicy.UNIT;
        final Deque<String> history = new ArrayDeque<>();
    }
    private final WorldRegistry registry;
    private final Map<Account, State> accounts = new HashMap<>();
    private Tag unreadable;
    long lastDay = Long.MIN_VALUE;

    final CurrencyMarket market;
    Ledger(WorldRegistry registry) {
        this.registry = registry;
        market = new CurrencyMarket(registry, this);
    }
    String currency(String code) { return market.resolve(code).code; }

    void available() { require(unreadable == null, "ledger_locked"); }
    private State state(Account account) {
        available();
        return accounts.computeIfAbsent(account, id -> new State());
    }
    long balance(Account account) {
        available();
        var row = accounts.get(account);
        return row == null ? 0 : row.balance;
    }
    List<String> history(Account account) {
        available();
        var row = accounts.get(account);
        return row == null ? List.of() : List.copyOf(row.history);
    }
    long rate(Account account, boolean tax) {
        available();
        var row = accounts.get(account);
        if (row == null) return 0;
        long base = tax ? row.tax : row.upkeep;
        if (!TerritoryConfig.INDEX_FEES.get() || base == 0) return base;
        return MonetaryPolicy.indexed(base, market.resolve(feeCurrency(account,tax)).index, tax ? row.taxIndex : row.upkeepIndex);
    }
    String feeCurrency(Account account, boolean tax) {
        available();
        var row = accounts.get(account);
        return row == null ? EconomyApi.REFERENCE : tax ? row.taxCode : row.upkeepCode;
    }
    void rate(Account account, boolean tax, long value) {
        require(value >= 0, "bad_amount");
        var row = state(account);
        String code = account.kind == Kind.TOWN ? registry.officialTownCurrency(account.id) : market.official(account.id);
        long index = market.resolve(code).index;
        if (tax) {
            row.tax = value;
            row.taxCode = code;
            row.taxIndex = index;
        } else {
            row.upkeep = value;
            row.upkeepCode = code;
            row.upkeepIndex = index;
        }
        registry.setDirty();
    }
    void firstLogin(UUID player) {
        var account = Account.player(player);
        var row = state(account);
        if (row.initialized) return;
        // An offline recipient may already have funds before their first login.
        long grant = Math.min(TerritoryConfig.STARTING.get(), Long.MAX_VALUE - row.balance);
        if (grant > 0) post(null, account, grant, "server", "starting balance");
        row.initialized = true;
        registry.setDirty();
    }

    void checkTransfer(Account from, Account to, long amount) {
        available();
        require(from == null || to == null || from.currency.equals(to.currency), "currency_invalid");
        require(amount > 0 && !Objects.equals(from, to), "bad_amount");
        if (from != null) require(balance(from) >= amount, "insufficient_funds");
        if (to != null) require(balance(to) <= Long.MAX_VALUE - amount, "balance_limit");
    }
    void settleGoods(TradeOffer offer) {
        String code = currency(offer.currency());
        Account payer = Account.player(offer.buyer()).in(code);
        Account seller = Account.player(offer.seller()).in(code);
        Account treasury = Account.nation(offer.destination()).in(code);
        require(balance(payer) >= offer.total(), "insufficient_funds");
        checkTransfer(payer, seller, offer.price());
        long duty = offer.duty();
        if (duty > 0) checkTransfer(payer, treasury, duty);
        String actor = offer.buyer().toString();
        post(payer, seller, offer.price(), actor, "goods sale " + offer.id());
        if (duty > 0) post(payer, treasury, duty, actor, "import duty " + offer.id());
        market.trade(payer, offer.price());
    }
    /** Null endpoints are reserved for internal issuance and sinks. */
    void post(Account from, Account to, long amount, String actor, String reason) {
        checkTransfer(from, to, amount);
        if (from != null) state(from).balance -= amount;
        if (to != null) state(to).balance += amount;
        if (reason.equals("payment") || reason.startsWith("property sale ")) {
            market.trade(from, amount);
        }
        record(from, to, amount, actor, reason);
    }
    void record(Account from, Account to, long amount, String actor, String reason) {
        String entry = java.time.Instant.now() + " | " + actor + " | " +
                Objects.toString(from, "system") + " -> " + Objects.toString(to, "system") + " | " + amount + " | " + reason;
        if (from != null) append(state(from), entry);
        if (to != null && !to.equals(from)) append(state(to), entry);
        registry.setDirty();
        LogUtils.getLogger().info("HNC economy {}", entry);
    }
    private static void append(State row, String entry) {
        if (row.history.size() == HISTORY_LIMIT) row.history.removeFirst();
        row.history.addLast(entry);
    }
    void checkClose(Account account, UUID leader) {
        available();
        for (Account holding : holdings(account)) checkPayout(holding, leader);
    }
    private List<Account> holdings(Account institution) {
        return accounts.keySet().stream().filter(a -> a.kind == institution.kind && a.id.equals(institution.id)
                && balance(a) > 0).toList();
    }
    private void checkPayout(Account account, UUID leader) {
        long funds = balance(account);
        require(TerritoryConfig.LIQUIDATION.get() == TerritoryConfig.Liquidation.PAY_LEADER, "bank_not_empty");
        checkTransfer(account, Account.player(leader).in(account.currency), funds);
    }
    void close(Account account, UUID leader) {
        checkClose(account, leader);
        for (Account holding : holdings(account))
            post(holding, Account.player(leader).in(holding.currency), balance(holding), leader.toString(), "liquidation");
        // Retain the institution's final statement under its retired UUID.
    }
    void save(CompoundTag root) {
        if (unreadable != null) { root.put("Economy", unreadable.copy()); return; }
        var tag = new CompoundTag();
        tag.putInt("Version", 2);
        tag.putLong("LastDay", lastDay);
        tag.put("Policies", market.save());
        var rows = new ListTag();
        accounts.forEach((account, row) -> {
            var item = new CompoundTag();
            item.putString("Kind", account.kind.name());
            item.putUUID("Id", account.id);
            item.putString("Currency", account.currency);
            item.putLong("Balance", row.balance);
            item.putLong("Tax", row.tax);
            item.putLong("Upkeep", row.upkeep);
            item.putString("TaxCurrency", row.taxCode);
            item.putString("UpkeepCurrency", row.upkeepCode);
            item.putLong("TaxIndex", row.taxIndex);
            item.putLong("UpkeepIndex", row.upkeepIndex);
            item.putBoolean("Initialized", row.initialized);
            var entries = new ListTag();
            row.history.forEach(line -> entries.add(StringTag.valueOf(line)));
            item.put("History", entries);
            rows.add(item);
        });
        tag.put("Accounts", rows);
        root.put("Economy", tag);
    }
    void load(CompoundTag root) {
        Tag raw = root.get("Economy");
        if (raw == null) return;
        try {
            if (!(raw instanceof CompoundTag tag) || (tag.getInt("Version") != 1 && tag.getInt("Version") != 2) ||
                    !tag.contains("LastDay", Tag.TAG_LONG) || !(tag.get("Accounts") instanceof ListTag rows))
                throw new IllegalArgumentException("header");
            lastDay = tag.getLong("LastDay");
            boolean legacy = tag.getInt("Version") == 1;
            if (!legacy) {
                if (!(tag.get("Policies") instanceof ListTag policies)) throw new IllegalArgumentException("policies");
                market.load(policies);
            }
            for (Tag element : rows) {
                var item = (CompoundTag) element;
                var code = tag.getInt("Version") == 1 ? EconomyApi.REFERENCE : item.getString("Currency");
                if (!code.matches("[A-Z]{3,6}")) throw new IllegalArgumentException("currency");
                var account = new Account(Kind.valueOf(item.getString("Kind")), item.getUUID("Id"), code);
                var row = new State();
                row.balance = money(item, "Balance");
                row.tax = money(item, "Tax");
                row.upkeep = money(item, "Upkeep");
                if (!item.contains("Initialized", Tag.TAG_BYTE) || !(item.get("History") instanceof ListTag entries))
                    throw new IllegalArgumentException("account metadata");
                if (!legacy) {
                    currency(code);
                    row.taxCode = currency(item.getString("TaxCurrency"));
                    row.upkeepCode = currency(item.getString("UpkeepCurrency"));
                    row.taxIndex = MonetaryPolicy.number(item,"TaxIndex",1,MonetaryPolicy.MAX_QUOTE);
                    row.upkeepIndex = MonetaryPolicy.number(item,"UpkeepIndex",1,MonetaryPolicy.MAX_QUOTE);
                }
                row.initialized = item.getBoolean("Initialized");
                for (Tag entry : entries) {
                    if (!(entry instanceof StringTag)) throw new IllegalArgumentException("history");
                    append(row, entry.getAsString());
                }
                if (accounts.putIfAbsent(account, row) != null) throw new IllegalArgumentException("duplicate account");
            }
            registry.nations().forEach(n -> market.bank(n.id()));
            if (legacy) {
                market.resolve(EconomyApi.REFERENCE).opening = supply(EconomyApi.REFERENCE);
                registry.setDirty();
            }
        } catch (RuntimeException invalid) {
            accounts.clear();
            unreadable = raw.copy();
            LogUtils.getLogger().error("Historical Nations economy locked. Restore the ledger from backup.", invalid);
        }
    }
    Map<String, BigInteger> supplies() {
        var amounts = new HashMap<String, BigInteger>();
        accounts.forEach((a,s) -> amounts.merge(a.currency, BigInteger.valueOf(s.balance), BigInteger::add));
        return amounts;
    }
    BigInteger supply(String code) {
        available();
        return accounts.entrySet().stream().filter(e -> e.getKey().currency.equals(code))
                .map(e -> BigInteger.valueOf(e.getValue().balance)).reduce(BigInteger.ZERO, BigInteger::add);
    }
    boolean locked() { return unreadable != null; }
    private static long money(CompoundTag item, String key) {
        if (!item.contains(key, Tag.TAG_LONG) || item.getLong(key) < 0) throw new IllegalArgumentException(key);
        return item.getLong(key);
    }
}
