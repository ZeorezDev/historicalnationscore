package dev.zeorez.historicalnations.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.EconomyApi.Bank;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static net.minecraft.commands.Commands.*;
import static dev.zeorez.historicalnations.core.Ledger.Account;
import static dev.zeorez.historicalnations.core.EconomyCommands.*;
import static dev.zeorez.historicalnations.core.MonetaryPolicy.REF;

final class CurrencyCommands {
    private static RequiredArgumentBuilder<CommandSourceStack,String> word(String key) { return argument(key,StringArgumentType.word()); }
    private static RequiredArgumentBuilder<CommandSourceStack,Long> amount() { return argument("amount",LongArgumentType.longArg(1)); }
    private static long amount(CommandContext<CommandSourceStack> c) { return LongArgumentType.getLong(c,"amount"); }
    private static String str(CommandContext<CommandSourceStack> c,String key) { return StringArgumentType.getString(c,key); }
    private static UUID player(CommandContext<CommandSourceStack> c) throws CommandSyntaxException { return c.getSource().getPlayerOrException().getUUID(); }
    private static WorldRegistry data(CommandContext<CommandSourceStack> c) { return WorldRegistry.get(c.getSource().getServer()); }
    private static CurrencyMarket market(CommandContext<CommandSourceStack> c) { return ledger(c).market; }
    private static String code(CommandContext<CommandSourceStack> c) { return ledger(c).currency(str(c,"currency")); }
    private static String pct(long bps) { return BigDecimal.valueOf(bps,2).toPlainString(); }
    private static Component nationInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var nation = data(c).country(player(c));
        var bank = market(c).bank(nation.id());
        var m = market(c).resolve(market(c).official(nation.id()));
        return msg("national_economy", nation.name(), m.code, pct(bank.inflation), MonetaryPolicy.decimal(bank.index),
                ledger(c).supply(m.code).toString(), MonetaryPolicy.decimal(m.quote), pct(bank.interest));
    }
    private static String label(WorldRegistry data, MonetaryPolicy m) {
        if (REF.equals(m.code)) return TerritoryConfig.CURRENCY.get() + " (" + TerritoryConfig.SYMBOL.get() + ", " + REF + ")";
        return m.name + " (" + m.symbol + ", " + m.code + ") " + data.getNationById(m.nation)
                .map(n -> n.name()).orElse("†");
    }
    static void register(CommandDispatcher<CommandSourceStack> d) {
        var nation = literal("nation");
        var currency = literal("currency");
        currency.then(literal("create").then(argument("name",StringArgumentType.string())
                .then(argument("symbol",StringArgumentType.string()).then(word("code").executes(run(c -> {
                    var issuer = data(c).ledNation(player(c));
                    market(c).create(issuer.id(),str(c,"name"),str(c,"symbol"),str(c,"code"));
                    return msg("updated");
                }))))));
        currency.then(literal("list").executes(run(c -> {
            ledger(c).available();
            return msg("currency_list",String.join("\n",market(c).currencies.values().stream().map(m -> label(data(c),m)).toList()));
        })));
        currency.then(literal("info").executes(run(c -> {
            var id = data(c).country(player(c)).id();
            return msg("currency_list", label(data(c),market(c).resolve(market(c).official(id))));
        })));
        nation.then(currency);
        nation.then(literal("economy").executes(run(CurrencyCommands::nationInfo)));
        var central = literal("centralbank").then(literal("info").executes(run(CurrencyCommands::nationInfo)));
        central.then(literal("issue").then(amount().executes(run(c -> {
            market(c).issue(player(c),amount(c),LocalDate.now(ZoneOffset.UTC).toEpochDay());
            return msg("updated");
        }))));
        central.then(literal("interest").then(word("rate").executes(run(c -> {
            var institution = market(c).bank(data(c).ledNation(player(c)).id());
            String input = str(c,"rate");
            WorldRegistry.require(input.matches("[0-9]{1,3}(\\.[0-9]{1,2})?"), "policy_invalid");
            institution.policy(new BigDecimal(input).movePointRight(2).longValueExact());
            data(c).setDirty();
            return msg("updated");
        }))));
        d.register(nation.then(central));

        var exchange = literal("exchange");
        exchange.then(literal("rates").executes(run(c -> {
            ledger(c).available();
            var lines = new ArrayList<String>();
            market(c).currencies.forEach((key,m) -> lines.add(label(data(c),m) + " = " + MonetaryPolicy.decimal(m.quote) + " " + REF
                    + " | " + ledger(c).balance(Account.reserve(key)) + " " + key));
            return msg("exchange_rates",String.join("\n",lines));
        })));
        exchange.then(literal("history").then(word("currency").executes(run(c ->
                msg("currency_history",code(c),String.join("\n",market(c).resolve(code(c)).history))))));
        exchange.then(literal("convert").then(word("from").then(word("to").then(amount().executes(run(c -> {
            long received = market(c).exchange(player(c),str(c,"from"),str(c,"to"),amount(c));
            return msg("payment_done",format(received,ledger(c).currency(str(c,"to"))));
        }))))));
        exchange.then(literal("fund").then(word("currency").then(amount().executes(run(c -> {
            String code = code(c);
            UUID id = player(c);
            ledger(c).post(Account.player(id).in(code),Account.reserve(code),amount(c),id.toString(),"reserve funding");
            return msg("payment_done",format(amount(c),code));
        })))));
        d.register(exchange);

        var money = literal("money");
        money.then(literal("balance").executes(run(c -> {
            var lines = new ArrayList<String>();
            UUID id = player(c);
            ledger(c).available();
            for (String code : market(c).currencies.keySet()) {
                long funds = ledger(c).balance(Account.player(id).in(code));
                if (funds != 0 || REF.equals(code)) lines.add(format(funds,code));
            }
            return msg("balance",String.join(", ",lines));
        })).then(word("currency").executes(run(c -> msg("balance",format(ledger(c).balance(Account.player(player(c)).in(code(c))),code(c)))))));
        money.then(literal("pay").then(argument("player",GameProfileArgument.gameProfile()).then(amount().then(word("currency").executes(run(c -> {
            Banking.get(c.getSource().getServer()).transfer(c.getSource().getPlayerOrException(),target(c),amount(c),code(c));
            return msg("payment_done",format(amount(c),code(c)));
        }))))));
        d.register(money);
        for (Bank type : Bank.values()) {
            var bank = literal("bank");
            bank.then(word("currency").executes(run(c -> {
                var account = Banking.get(c.getSource().getServer()).bank(c.getSource().getPlayerOrException(),type,false).in(code(c));
                return msg("balance",format(ledger(c).balance(account),account.currency()));
            })));
            bank.then(literal("history").then(word("currency").executes(run(c -> {
                var account = Banking.get(c.getSource().getServer()).bank(c.getSource().getPlayerOrException(),type,false).in(code(c));
                return msg("currency_history",code(c),String.join("\n",ledger(c).history(account)));
            }))));
            for (String verb : List.of("deposit","withdraw")) bank.then(literal(verb).then(amount().then(word("currency").executes(run(c -> {
                var api = Banking.get(c.getSource().getServer());
                var actor = c.getSource().getPlayerOrException();
                if (verb.equals("deposit")) api.deposit(actor,type,amount(c),code(c));
                else api.withdraw(actor,type,amount(c),code(c));
                return msg("payment_done",format(amount(c),code(c)));
            })))));
            d.register(literal(type.name().toLowerCase(Locale.ROOT)).then(bank));
        }
    }
}
