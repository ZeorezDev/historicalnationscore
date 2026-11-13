package dev.zeorez.historicalnations.core;

import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.*;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.EconomyApi.Bank;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import java.util.*;
import static net.minecraft.commands.Commands.*;
import static dev.zeorez.historicalnations.core.Ledger.Account;
import static dev.zeorez.historicalnations.core.WorldRegistry.require;

public final class EconomyCommands {
    private EconomyCommands() {}
    public static String format(long value) {
        return TerritoryConfig.SYMBOL.get() + value + " " + TerritoryConfig.CURRENCY.get();
    }
    private static Component msg(String key, Object... args) { return Component.translatable("historical_nations." + key, args); }
    private interface Action { Component run(CommandContext<CommandSourceStack> c) throws CommandSyntaxException; }
    private static Command<CommandSourceStack> run(Action action) {
        return c -> {
            try {
                var message = action.run(c);
                c.getSource().sendSuccess(() -> message, false);
                return 1;
            } catch (RuleViolation error) {
                throw new SimpleCommandExceptionType(msg(error.getMessage())).create();
            }
        };
    }
    private static UUID target(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var players = GameProfileArgument.getGameProfiles(c, "player");
        require(players.size() == 1, "single_player");
        return players.iterator().next().getId();
    }
    private static long amount(CommandContext<CommandSourceStack> c) { return LongArgumentType.getLong(c,"amount"); }
    private static Banking service(CommandContext<CommandSourceStack> c) { return Banking.get(c.getSource().getServer()); }
    private static Ledger ledger(CommandContext<CommandSourceStack> c) { return WorldRegistry.get(c.getSource().getServer()).ledger; }
    private static Component statement(Ledger ledger, Account account) {
        var message = msg("account", account.toString(), format(ledger.balance(account))).copy();
        for (String entry : ledger.history(account)) message.append("\n" + entry);
        return message;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var money = literal("money").executes(run(c -> msg("balance", format(service(c)
                .getBalance(c.getSource().getPlayerOrException().getUUID())))));
        money.then(literal("pay").then(argument("player", GameProfileArgument.gameProfile())
                .then(argument("amount", LongArgumentType.longArg(1)).executes(run(c -> {
                    service(c).transfer(c.getSource().getPlayerOrException(), target(c), amount(c));
                    return msg("payment_done", format(amount(c)));
                })))));
        dispatcher.register(money);
        for (Bank kind : Bank.values()) {
            var root = literal(kind.name().toLowerCase(Locale.ROOT));
            var bank = literal("bank").executes(run(c -> {
                var account = service(c).bank(c.getSource().getPlayerOrException(), kind, false);
                return msg("balance", format(ledger(c).balance(account)));
            }));
            bank.then(literal("history").executes(run(c -> statement(ledger(c),
                    service(c).bank(c.getSource().getPlayerOrException(), kind, false)))));
            for (String operation : List.of("deposit", "withdraw"))
                bank.then(literal(operation).then(argument("amount", LongArgumentType.longArg(1)).executes(run(c -> {
                    var player = c.getSource().getPlayerOrException();
                    if (operation.equals("deposit")) service(c).deposit(player, kind, amount(c));
                    else service(c).withdraw(player, kind, amount(c));
                    return msg("payment_done", format(amount(c)));
                }))));
            root.then(bank);
            for (String name : List.of("tax", "upkeep")) {
                boolean tax = name.equals("tax");
                var rate = literal(name).executes(run(c -> {
                    var account = service(c).bank(c.getSource().getPlayerOrException(), kind, false);
                    return msg("daily_amount", format(ledger(c).rate(account, tax)));
                }));
                rate.then(literal("set").then(argument("amount", LongArgumentType.longArg(0)).executes(run(c -> {
                    var account = service(c).bank(c.getSource().getPlayerOrException(), kind, true);
                    ledger(c).rate(account, tax, amount(c));
                    return msg("daily_amount", format(amount(c)));
                }))));
                root.then(rate);
            }
            dispatcher.register(root);
        }
        var admin = literal("economy").requires(source -> source.hasPermission(2));
        for (String operation : List.of("give", "take", "set", "info")) {
            var command = literal(operation);
            for (var kind : Ledger.Kind.values()) {
                ArgumentBuilder<CommandSourceStack, ?> account = kind == Ledger.Kind.PLAYER
                        ? argument("player", GameProfileArgument.gameProfile()) : argument("name", StringArgumentType.string());
                if (operation.equals("info")) account.executes(run(c -> statement(ledger(c), account(c,kind))));
                else {
                    var value = argument("amount", LongArgumentType.longArg(operation.equals("set") ? 0 : 1));
                    value.executes(run(c -> adjust(c, kind, operation, "operator " + operation)));
                    value.then(argument("reason", StringArgumentType.greedyString())
                            .executes(run(c -> adjust(c, kind, operation, StringArgumentType.getString(c,"reason")))));
                    account.then(value);
                }
                command.then(literal(kind.name().toLowerCase(Locale.ROOT)).then(account));
            }
            admin.then(command);
        }
        dispatcher.register(admin);
    }
    private static Account account(CommandContext<CommandSourceStack> c, Ledger.Kind kind) throws CommandSyntaxException {
        var data = WorldRegistry.get(c.getSource().getServer());
        return switch (kind) {
            case PLAYER -> Account.player(target(c));
            case TOWN -> Account.town(data.namedTown(StringArgumentType.getString(c,"name")).id());
            case NATION -> Account.nation(data.namedNation(StringArgumentType.getString(c,"name")).id());
        };
    }
    private static Component adjust(CommandContext<CommandSourceStack> c, Ledger.Kind kind, String operation, String reason)
            throws CommandSyntaxException {
        require(c.getSource().hasPermission(2), "invalid_actor");
        require(!reason.isBlank() && reason.length() <= 160 && reason.chars().noneMatch(Character::isISOControl), "invalid_text");
        var ledger = ledger(c);
        var account = account(c, kind);
        long amount = amount(c);
        long delta = switch (operation) {
            case "set" -> amount - ledger.balance(account);
            case "take" -> -amount;
            default -> amount;
        };
        String actor = c.getSource().getEntity() == null ? c.getSource().getTextName() : c.getSource().getEntity().getUUID().toString();
        String audit = operation + " " + amount + " | " + reason;
        if (delta == 0) ledger.record(null, account, 0, actor, audit);
        else if (delta > 0) ledger.post(null, account, delta, actor, audit);
        else ledger.post(account, null, -delta, actor, audit);
        return msg("account", account.toString(), format(ledger.balance(account)));
    }
}
