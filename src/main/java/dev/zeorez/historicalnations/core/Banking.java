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
    private UUID actor(ServerPlayer player) {
        require(player.server == server, "invalid_actor");
        return player.getUUID();
    }
    public long getBalance(UUID player) { return data().ledger.balance(Account.player(player)); }
    public long getTownBalance(UUID town) {
        var registry = data();
        require(registry.getTownById(town).isPresent(), "unknown_town");
        return registry.ledger.balance(Account.town(town));
    }
    public long getNationBalance(UUID nation) {
        var registry = data();
        require(registry.getNationById(nation).isPresent(), "unknown_nation");
        return registry.ledger.balance(Account.nation(nation));
    }
    public void transfer(ServerPlayer payer, UUID recipient, long amount) {
        var id = actor(payer);
        data().ledger.post(Account.player(id), Account.player(Objects.requireNonNull(recipient)), amount, id.toString(), "payment");
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
    public void deposit(ServerPlayer member, Bank kind, long amount) {
        var target = bank(member, kind, false);
        var id = member.getUUID();
        data().ledger.post(Account.player(id), target, amount, id.toString(), "deposit");
    }
    public void withdraw(ServerPlayer leader, Bank kind, long amount) {
        var source = bank(leader, kind, true);
        var id = leader.getUUID();
        data().ledger.post(source, Account.player(id), amount, id.toString(), "withdrawal");
    }
    public void paySale(ServerPlayer buyer, UUID town, UUID seller, long price, UUID property) {
        var id = actor(buyer);
        var from = Account.player(id);
        var to = seller == null ? Account.town(town) : Account.player(seller);
        require(!id.equals(seller), "bad_amount");
        var ledger = data().ledger;
        if (price == 0) ledger.record(from, to, 0, id.toString(), "free allocation " + property);
        else ledger.post(from, to, price, id.toString(), "property sale " + property);
    }
}
