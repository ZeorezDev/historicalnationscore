package dev.zeorez.historicalnations.core;

import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.TerritoryConfig;
import net.minecraft.nbt.*;
import java.util.*;
import static dev.zeorez.historicalnations.core.WorldRegistry.require;

/** Part of the registry snapshot, so a sale and its payment share a save boundary. */
final class Ledger {
    enum Kind { PLAYER, TOWN, NATION }
    record Account(Kind kind, UUID id) {
        static Account player(UUID id) { return new Account(Kind.PLAYER, id); }
        static Account town(UUID id) { return new Account(Kind.TOWN, id); }
        static Account nation(UUID id) { return new Account(Kind.NATION, id); }
        @Override public String toString() { return kind + "/" + id; }
    }
    static final int HISTORY_LIMIT = 32;
    static final class State {
        long balance, tax, upkeep;
        boolean initialized;
        final Deque<String> history = new ArrayDeque<>();
    }
    private final WorldRegistry registry;
    private final Map<Account, State> accounts = new HashMap<>();
    private Tag unreadable;
    long lastDay = Long.MIN_VALUE;

    Ledger(WorldRegistry registry) { this.registry = registry; }

    private void available() { require(unreadable == null, "ledger_locked"); }
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
        return row == null ? 0 : tax ? row.tax : row.upkeep;
    }
    void rate(Account account, boolean tax, long value) {
        require(value >= 0, "bad_amount");
        var row = state(account);
        if (tax) row.tax = value;
        else row.upkeep = value;
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
        require(amount > 0 && !Objects.equals(from, to), "bad_amount");
        if (from != null) require(balance(from) >= amount, "insufficient_funds");
        if (to != null) require(balance(to) <= Long.MAX_VALUE - amount, "balance_limit");
    }
    /** Null endpoints are reserved for internal issuance and sinks. */
    void post(Account from, Account to, long amount, String actor, String reason) {
        checkTransfer(from, to, amount);
        if (from != null) state(from).balance -= amount;
        if (to != null) state(to).balance += amount;
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
        long funds = balance(account);
        if (funds == 0) return;
        require(TerritoryConfig.LIQUIDATION.get() == TerritoryConfig.Liquidation.PAY_LEADER, "bank_not_empty");
        checkTransfer(account, Account.player(leader), funds);
    }
    void close(Account account, UUID leader) {
        checkClose(account, leader);
        long funds = balance(account);
        if (funds > 0) post(account, Account.player(leader), funds, leader.toString(), "liquidation");
        // Retain the institution's final statement under its retired UUID.
    }
    void save(CompoundTag root) {
        if (unreadable != null) { root.put("Economy", unreadable.copy()); return; }
        var tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putLong("LastDay", lastDay);
        var rows = new ListTag();
        accounts.forEach((account, row) -> {
            var item = new CompoundTag();
            item.putString("Kind", account.kind.name());
            item.putUUID("Id", account.id);
            item.putLong("Balance", row.balance);
            item.putLong("Tax", row.tax);
            item.putLong("Upkeep", row.upkeep);
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
            if (!(raw instanceof CompoundTag tag) || tag.getInt("Version") != 1 ||
                    !tag.contains("LastDay", Tag.TAG_LONG) || !(tag.get("Accounts") instanceof ListTag rows))
                throw new IllegalArgumentException("header");
            lastDay = tag.getLong("LastDay");
            for (Tag element : rows) {
                var item = (CompoundTag) element;
                var account = new Account(Kind.valueOf(item.getString("Kind")), item.getUUID("Id"));
                var row = new State();
                row.balance = money(item, "Balance");
                row.tax = money(item, "Tax");
                row.upkeep = money(item, "Upkeep");
                if (!item.contains("Initialized", Tag.TAG_BYTE) || !(item.get("History") instanceof ListTag entries))
                    throw new IllegalArgumentException("account metadata");
                row.initialized = item.getBoolean("Initialized");
                for (Tag entry : entries) {
                    if (!(entry instanceof StringTag)) throw new IllegalArgumentException("history");
                    append(row, entry.getAsString());
                }
                if (accounts.putIfAbsent(account, row) != null) throw new IllegalArgumentException("duplicate account");
            }
        } catch (RuntimeException invalid) {
            accounts.clear();
            unreadable = raw.copy();
            LogUtils.getLogger().error("Historical Nations economy locked. Restore the ledger from backup.", invalid);
        }
    }
    boolean locked() { return unreadable != null; }
    private static long money(CompoundTag item, String key) {
        if (!item.contains(key, Tag.TAG_LONG) || item.getLong(key) < 0) throw new IllegalArgumentException(key);
        return item.getLong(key);
    }
}
