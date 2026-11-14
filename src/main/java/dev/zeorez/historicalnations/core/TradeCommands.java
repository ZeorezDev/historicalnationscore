package dev.zeorez.historicalnations.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import java.math.BigDecimal;
import java.util.*;
import static net.minecraft.commands.Commands.*;
import static dev.zeorez.historicalnations.core.EconomyCommands.*;
import static dev.zeorez.historicalnations.core.WorldRegistry.require;

public final class TradeCommands {
    private TradeCommands() {}
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer().getTickCount()%20 == 0)
            WorldRegistry.get(event.getServer()).trade.expire();
    }
    private static WorldRegistry data(CommandContext<CommandSourceStack> c) { return WorldRegistry.get(c.getSource().getServer()); }
    private static String names(WorldRegistry data, Collection<UUID> ids) {
        return String.join(", ",ids.stream().map(id -> data.getNationById(id).map(n -> n.name()).orElse(id.toString())).sorted().toList());
    }
    private static Component embargoes(WorldRegistry data,UUID id) {
        return msg("trade_bans",names(data,data.trade.bans(id)),names(data,data.trade.bannedBy(id)));
    }
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var trade = literal("trade");
        trade.then(literal("offer").then(argument("player",EntityArgument.player()).then(argument("amount",IntegerArgumentType.integer(1,64))
                .then(argument("price",LongArgumentType.longArg(1)).then(argument("currency",StringArgumentType.word()).executes(run(c -> {
                    var seller = c.getSource().getPlayerOrException();
                    var buyer = EntityArgument.getPlayer(c,"player");
                    var o = data(c).trade.offer(seller,buyer,IntegerArgumentType.getInteger(c,"amount"),
                            LongArgumentType.getLong(c,"price"),StringArgumentType.getString(c,"currency"));
                    buyer.sendSystemMessage(msg("trade_invite",seller.getDisplayName(),o.quote(),o.id().toString()));
                    return o.quote();
                })))))));
        for (String verb : List.of("accept","deny","cancel","info")) {
            trade.then(literal(verb).then(argument("id",UuidArgument.uuid()).executes(run(c -> {
                var book = data(c).trade;
                var actor = c.getSource().getPlayerOrException();
                var id = UuidArgument.getUuid(c,"id");
                var o = book.find(id,actor.getUUID());
                if (verb.equals("info")) return o.quote();
                if (verb.equals("accept")) {
                    book.accept(actor,o);
                    var receipt = msg("trade_done",o.quote());
                    var seller = actor.server.getPlayerList().getPlayer(o.seller());
                    if (seller != null) seller.sendSystemMessage(receipt);
                    return receipt;
                }
                book.dismiss(id,actor.getUUID(),verb.equals("deny"));
                return msg("trade_closed",id.toString());
            }))));
        }
        d.register(trade);

        var nation = literal("nation");
        var tariff = literal("tariff");
        tariff.then(literal("info").executes(run(c -> {
            var data = data(c);
            return msg("trade_tariff", BigDecimal.valueOf(data.trade.tariff(data.country(c.getSource().getPlayerOrException().getUUID()).id()),2).toPlainString());
        })));
        tariff.then(literal("set").then(argument("percent",StringArgumentType.word()).executes(run(c -> {
            String input = StringArgumentType.getString(c,"percent");
            require(input.matches("[0-9]{1,2}(\\.[0-9]{1,2})?"), "trade_rate");
            data(c).trade.tariff(c.getSource().getPlayerOrException().getUUID(),new BigDecimal(input).movePointRight(2).intValueExact());
            return msg("trade_tariff",input);
        }))));
        tariff.then(literal("clear").executes(run(c -> {
            data(c).trade.tariff(c.getSource().getPlayerOrException().getUUID(),0);
            return msg("trade_tariff","0");
        })));
        nation.then(tariff);
        var embargo = literal("embargo");
        for (String verb : List.of("add","remove")) embargo.then(literal(verb).then(argument("nation",StringArgumentType.greedyString()).executes(run(c -> {
            var data = data(c);
            UUID player = c.getSource().getPlayerOrException().getUUID();
            data.trade.embargo(player,data.namedNation(StringArgumentType.getString(c,"nation")).id(),verb.equals("add"));
            return embargoes(data,data.country(player).id());
        }))));
        embargo.then(literal("list").executes(run(c -> embargoes(data(c),data(c).country(c.getSource().getPlayerOrException().getUUID()).id()))));
        nation.then(embargo);
        var reports = literal("trade");
        for (String verb : List.of("stats","imports","exports")) reports.then(literal(verb).executes(run(c -> {
            var data = data(c);
            UUID id = data.country(c.getSource().getPlayerOrException().getUUID()).id();
            data.trade.available();
            if (verb.equals("stats")) return msg("trade_stats",data.trade.stats.report(data,id,true,false),
                    data.trade.stats.report(data,id,false,false),embargoes(data,id));
            return data.trade.stats.report(data,id,verb.equals("imports"),true);
        })));
        d.register(nation.then(reports));
    }
}
