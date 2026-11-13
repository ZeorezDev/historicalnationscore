package dev.zeorez.historicalnations.core;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import static dev.zeorez.historicalnations.core.Ledger.Account;

public final class DailyFinance {
    private DailyFinance() {}
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var ledger = WorldRegistry.get(player.server).ledger;
            if (!ledger.locked()) ledger.firstLogin(player.getUUID());
        }
    }
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer().getTickCount() % 20 == 0)
            settle(event.getServer(), LocalDate.now(ZoneOffset.UTC).toEpochDay());
    }
    static void settle(MinecraftServer server, long day) {
        var data = WorldRegistry.get(server);
        var ledger = data.ledger;
        if (ledger.locked() || day <= ledger.lastDay) return;
        boolean first = ledger.lastDay == Long.MIN_VALUE;
        ledger.lastDay = day;
        data.setDirty();
        if (first) return;

        for (var town : data.towns()) {
            var bank = Account.town(town.id());
            long tax = ledger.rate(bank, true);
            if (tax > 0) for (UUID citizen : data.members(town.id()))
                collect(server, ledger, Account.player(citizen), bank, tax, "town tax", citizen, town.mayor());
        }
        for (var nation : data.nations()) {
            var bank = Account.nation(nation.id());
            long tax = ledger.rate(bank, true);
            if (tax > 0) for (var town : data.getNationTowns(nation.id()))
                collect(server, ledger, Account.town(town.id()), bank, tax, "nation tax", town.mayor(), data.towns.get(nation.capitalTown()).mayor());
        }
        for (var town : data.towns()) upkeep(server, ledger, Account.town(town.id()), town.mayor());
        for (var nation : data.nations())
            upkeep(server, ledger, Account.nation(nation.id()), data.towns.get(nation.capitalTown()).mayor());
    }
    private static void upkeep(MinecraftServer server, Ledger ledger, Account bank, UUID leader) {
        long amount = ledger.rate(bank, false);
        if (amount > 0) collect(server, ledger, bank, null, amount, "upkeep", leader, leader);
    }
    private static void collect(MinecraftServer server, Ledger ledger, Account from, Account to,
                                long amount, String reason, UUID payer, UUID recipient) {
        try {
            ledger.post(from, to, amount, "server", reason);
        } catch (RuleViolation rejected) {
            ledger.record(from, to, amount, "server", "UNPAID " + reason + " (" + rejected.getMessage() + ")");
            var message = Component.translatable("historical_nations.unpaid", from.toString(), EconomyCommands.format(amount),
                    Component.translatable("historical_nations." + rejected.getMessage()));
            for (UUID id : new LinkedHashSet<>(List.of(payer, recipient))) {
                var player = server.getPlayerList().getPlayer(id);
                if (player != null) player.sendSystemMessage(message);
            }
        }
    }
}
