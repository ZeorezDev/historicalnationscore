package dev.zeorez.historicalnations.core;

import dev.zeorez.historicalnations.api.EconomyApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import static dev.zeorez.historicalnations.core.Ledger.Account;
import static dev.zeorez.historicalnations.core.WorldRegistry.require;

/** Checks the server thread on every call, including calls through a retained API handle. */
public final class Banking implements EconomyApi {
    private final MinecraftServer server;
    private Banking(MinecraftServer server) { this.server = server; }
    public static Banking get(MinecraftServer server) { return new Banking(server); }
    private WorldRegistry data() { return WorldRegistry.get(server); }
    public String currency(String code) { return data().ledger.currency(code); }
    private UUID actor(ServerPlayer player) {
        require(player.server == server, "invalid_actor");
        return player.getUUID();
    }
    public long getBalance(UUID player) { return getBalance(player, REFERENCE); }
    public long getBalance(UUID player, String currency) { return data().ledger.balance(Account.player(player).in(currency(currency))); }
    public long getTownBalance(UUID town) { return getTownBalance(town, REFERENCE); }
    public long getTownBalance(UUID town, String currency) {
        var registry = data();
        require(registry.getTownById(town).isPresent(), "unknown_town");
        return registry.ledger.balance(Account.town(town).in(currency(currency)));
    }
    public long getNationBalance(UUID nation) { return getNationBalance(nation, REFERENCE); }
    public long getNationBalance(UUID nation, String currency) {
        var registry = data();
        require(registry.getNationById(nation).isPresent(), "unknown_nation");
        return registry.ledger.balance(Account.nation(nation).in(currency(currency)));
    }
    public void transfer(ServerPlayer payer, UUID recipient, long amount) { transfer(payer, recipient, amount, REFERENCE); }
    public void transfer(ServerPlayer payer, UUID recipient, long amount, String currency) {
        var id = actor(payer);
        String code = currency(currency);
        data().ledger.post(Account.player(id).in(code), Account.player(Objects.requireNonNull(recipient)).in(code), amount, id.toString(), "payment");
    }
    Ledger.Account bank(ServerPlayer member, Bank kind, boolean leadership) {
        var id = actor(member);
        var registry = data();
        if (kind == Bank.TOWN) {
            var town = registry.home(id);
            require(!leadership || town.mayor().equals(id), "mayor_only");
            return Account.town(town.id());
        }
        var nation = registry.country(id);
        require(!leadership || registry.isNationLeader(id), "leader_only");
        return Account.nation(nation.id());
    }
    public void deposit(ServerPlayer member, Bank kind, long amount) { deposit(member, kind, amount, REFERENCE); }
    public void deposit(ServerPlayer member, Bank kind, long amount, String currency) {
        var target = bank(member, kind, false);
        var id = member.getUUID();
        String code = currency(currency);
        data().ledger.post(Account.player(id).in(code), target.in(code), amount, id.toString(), "deposit");
    }
    public void withdraw(ServerPlayer leader, Bank kind, long amount) { withdraw(leader, kind, amount, REFERENCE); }
    public void withdraw(ServerPlayer leader, Bank kind, long amount, String currency) {
        var source = bank(leader, kind, true);
        var id = leader.getUUID();
        String code = currency(currency);
        data().ledger.post(source.in(code), Account.player(id).in(code), amount, id.toString(), "withdrawal");
    }
    public void paySale(ServerPlayer buyer, UUID town, UUID seller, long price, UUID property) { paySale(buyer, town, seller, price, property, REFERENCE); }
    public void paySale(ServerPlayer buyer, UUID town, UUID seller, long price, UUID property, String currency) {
        var id = actor(buyer);
        var from = Account.player(id).in(currency(currency));
        var to = (seller == null ? Account.town(town) : Account.player(seller)).in(from.currency());
        require(!id.equals(seller), "bad_amount");
        var ledger = data().ledger;
        if (price == 0) ledger.record(from, to, 0, id.toString(), "free allocation " + property);
        else ledger.post(from, to, price, id.toString(), "property sale " + property);
    }
}
