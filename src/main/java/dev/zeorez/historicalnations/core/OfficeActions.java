package dev.zeorez.historicalnations.core;

import dev.zeorez.historicalnations.api.*;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** This allowlist is used both when rendering the office and immediately before dispatch. */
public enum OfficeActions {
    INVITE("invite","player_arg"), KICK("kick","player_arg",true), DESCRIPTION("description set","description"),
    CLEAR_DESCRIPTION("description clear",""), DEPUTY("deputy set","player_arg"), REMOVE_DEPUTY("deputy remove","",true),
    MAYOR("setmayor","player_arg",true), LEAVE("leave","",true), DISBAND("disband","",true),
    DEPOSIT("bank deposit","amount_currency"), WITHDRAW("bank withdraw","amount_currency"),
    TAX("tax set","amount"), UPKEEP("upkeep set","amount"), INVITE_TOWN("invite","town"),
    CREATE_CURRENCY("currency create","currency_args"), ISSUE("centralbank issue","amount",true),
    INTEREST("centralbank interest","percent"), TARIFF("tariff set","percent"),
    EMBARGO("embargo add","nation",true), LIFT("embargo remove","nation"),
    RENAME("rename","name_arg"), COLOR("color","color_arg"), CAPITAL("setcapital","town",true),
    SPAWN("spawn",""), SET_SPAWN("set spawn",""),
    ALLY("ally","nation"), UNALLY("unally","nation",true),
    DECLARE_WAR("war declare","nation",true), JOIN_WAR("war join","nation",true), PEACE_TREATY("war peace","nation");

    private final String command;
    public final String input;
    public final boolean confirm;
    OfficeActions(String command,String input) { this(command,input,false); }
    OfficeActions(String command,String input,boolean confirm) {
        this.command=command; this.input=input; this.confirm=confirm;
    }
    static EnumSet<OfficeActions> allowed(WorldRegistry d, UUID actor, UUID target, boolean national) {
        var result = EnumSet.noneOf(OfficeActions.class);
        Town town = d.getPlayerTown(actor).orElse(null);
        if (town == null) return result;
        boolean mayor = town.mayor().equals(actor);
        Nation nation = d.getTownNation(town.id()).orElse(null);
        if (national) {
            if (nation == null || !nation.id().equals(target)) return result;
            result.add(SPAWN);
            result.add(DEPOSIT);
            if (mayor && !town.id().equals(nation.capitalTown())) result.add(LEAVE);
            if (mayor && town.id().equals(nation.capitalTown())) {
                result.addAll(List.of(SET_SPAWN,DECLARE_WAR,PEACE_TREATY,JOIN_WAR,ALLY,UNALLY,RENAME,COLOR,CAPITAL,WITHDRAW,TAX,UPKEEP,INVITE_TOWN,INTEREST,TARIFF,EMBARGO,LIFT,DISBAND));
                result.add(d.ledger.market.bank(target).code.isEmpty() ? CREATE_CURRENCY : ISSUE);
            }
        } else if (town.id().equals(target)) {
            result.add(SPAWN);
            result.add(DEPOSIT);
            if (!mayor) result.add(LEAVE);
            if (mayor || actor.equals(d.deputy(target))) result.addAll(List.of(INVITE,KICK,DESCRIPTION,CLEAR_DESCRIPTION));
            if (mayor) {
                result.addAll(List.of(SET_SPAWN,RENAME,WITHDRAW,TAX,UPKEEP,DEPUTY,MAYOR));
                if (d.deputy(target)!=null) result.add(REMOVE_DEPUTY);
                if (nation==null || !town.id().equals(nation.capitalTown())) result.add(DISBAND);
            }
        }
        return result;
    }

    public record Result(boolean success, Component message) {}
    public Result perform(ServerPlayer player, UUID target, boolean national, String value) {
        var data=WorldRegistry.get(player.server);
        if (!allowed(data,player.getUUID(),target,national).contains(this))
            return new Result(false, Component.translatable("hn.panel.denied"));
        String args=value.strip();
        if (args.length()>256 || args.chars().anyMatch(Character::isISOControl) || input.isEmpty()!=args.isEmpty())
            return new Result(false, Component.translatable("hn.panel.invalid"));
        var feedback = new CommandSource() {
            Component last = Component.translatable("historical_nations.updated");
            public void sendSystemMessage(Component message) { last=message; }
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
        };
        String line = command.startsWith("war ")
                ? (command + (args.isEmpty() ? "" : " " + args))
                : ((national ? "nation " : "town ") + command + (args.isEmpty() ? "" : " " + args));
        int result=player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withSource(feedback),line);
        return new Result(result>0,feedback.last);
    }
}
