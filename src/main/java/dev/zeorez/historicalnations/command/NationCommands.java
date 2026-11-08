package dev.zeorez.historicalnations.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.core.RuleViolation;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.integration.OpenPacClaims;
import dev.zeorez.historicalnations.integration.TownTerritories;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class NationCommands {
    private NationCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var town = literal("town");
        town.then(named("create", (c, name) -> msg("town_created", c.data.createTown(c.id(), name).name())));
        town.then(literal("invite").then(argument("player", GameProfileArgument.gameProfile()).executes(run(c -> {
            GameProfile target = c.target();
            Town home = c.data.invitePlayer(c.id(), target.getId());
            c.notifyPlayer(target.getId(), msg("town_invitation", home.name()));
            return msg("invited", target.getName());
        }))));
        town.then(named("accept", (c, name) -> c.answerTown(name, true)));
        town.then(named("deny", (c, name) -> c.answerTown(name, false)));
        town.then(literal("leave").executes(run(c -> {
            c.data.leaveTown(c.id());
            return msg("town_left");
        })));
        town.then(named("info", (c, name) -> c.townInfo(c.data.namedTown(name)))
                .executes(run(c -> c.townInfo(c.data.home(c.id())))));
        town.then(literal("list").executes(run(c -> msg("towns", names(c.data.towns().stream().map(Town::name).toList())))));
        town.then(literal("members").executes(run(c -> msg("members", names(c.data.members(c.data.home(c.id()).id())
                .stream().map(c::playerName).toList())))));
        town.then(literal("setmayor").then(argument("player", GameProfileArgument.gameProfile()).executes(run(c -> {
            GameProfile target = c.target();
            c.land().transferMayor(c.player, target.getId());
            return msg("new_mayor", target.getName());
        }))));
        town.then(literal("disband").executes(run(c -> {
            c.land().disband(c.player);
            return msg("town_disbanded");
        })));
        town.then(literal("claimstatus").executes(run(Context::claimStatus)));
        town.then(literal("claim").executes(run(c -> {
            c.land().claim(c.player);
            return msg("land_claimed", c.data.home(c.id()).name());
        })));
        town.then(literal("unclaim").executes(run(c -> {
            c.land().unclaim(c.player);
            return msg("land_released");
        })));
        town.then(literal("claims").executes(run(c -> {
            Town home = c.data.home(c.id());
            return msg("land_count", home.name(), c.land().count(home.id()));
        })));
        town.then(literal("claiminfo").executes(run(c -> {
            var pos = TownTerritories.location(c.player);
            var link = c.land().verified(pos);
            Component townName = link == null ? msg("none") : Component.literal(c.data.getTownById(link.town()).orElseThrow().name());
            return msg("land_info", pos.dimension().toString(), pos.x(), pos.z(), townName);
        })));
        dispatcher.register(town);

        var nation = literal("nation");
        nation.then(named("create", (c, name) -> msg("nation_created", c.data.createNation(c.id(), name).name())));
        nation.then(named("invite", (c, name) -> {
            Town target = c.data.namedTown(name);
            Nation country = c.data.inviteTown(c.id(), target);
            c.notifyPlayer(target.mayor(), msg("nation_invitation", country.name()));
            return msg("invited", target.name());
        }));
        nation.then(named("accept", (c, name) -> c.answerNation(name, true)));
        nation.then(named("deny", (c, name) -> c.answerNation(name, false)));
        nation.then(literal("leave").executes(run(c -> {
            c.data.leaveNation(c.id());
            return msg("nation_left");
        })));
        nation.then(named("info", (c, name) -> c.nationInfo(c.data.namedNation(name)))
                .executes(run(c -> c.nationInfo(c.data.country(c.id())))));
        nation.then(literal("list").executes(run(c -> msg("nations", names(c.data.nations().stream().map(Nation::name).toList())))));
        nation.then(literal("towns").executes(run(c -> msg("towns", names(c.data.getNationTowns(c.data.country(c.id()).id())
                .stream().map(Town::name).toList())))));
        nation.then(literal("disband").executes(run(c -> {
            c.data.disbandNation(c.id());
            return msg("nation_disbanded");
        })));
        dispatcher.register(nation);
    }

    private interface Action {
        Component execute(Context context) throws CommandSyntaxException;
    }
    private interface NamedAction {
        Component execute(Context context, String name);
    }
    private static LiteralArgumentBuilder<CommandSourceStack> named(String verb, NamedAction action) {
        return literal(verb).then(argument("name", StringArgumentType.greedyString()).executes(
                run(c -> action.execute(c, StringArgumentType.getString(c.command, "name")))));
    }
    private static Command<CommandSourceStack> run(Action action) {
        return command -> {
            var player = command.getSource().getPlayerOrException();
            var context = new Context(command, player, WorldRegistry.get(command.getSource().getServer()));
            try {
                Component result = action.execute(context);
                command.getSource().sendSuccess(() -> result, false);
                return Command.SINGLE_SUCCESS;
            } catch (RuleViolation error) {
                throw new SimpleCommandExceptionType(msg(error.getMessage())).create();
            }
        };
    }
    private static Component msg(String key, Object... args) {
        return Component.translatable("historical_nations." + key, args);
    }
    private static Component names(List<String> names) {
        return names.isEmpty() ? msg("none") : Component.literal(String.join(", ", names));
    }

    private record Context(CommandContext<CommandSourceStack> command, ServerPlayer player, WorldRegistry data) {
        UUID id() { return player.getUUID(); }
        MinecraftServer server() { return command.getSource().getServer(); }
        TownTerritories land() { return new TownTerritories(server()); }

        GameProfile target() throws CommandSyntaxException {
            Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(command, "player");
            if (profiles.size() != 1) throw new SimpleCommandExceptionType(msg("single_player")).create();
            return profiles.iterator().next();
        }
        void notifyPlayer(UUID id, Component message) {
            var online = server().getPlayerList().getPlayer(id);
            if (online != null) online.sendSystemMessage(message);
        }
        String playerName(UUID id) {
            return server().getProfileCache().get(id).map(GameProfile::getName).orElse(id.toString());
        }
        Component answerTown(String name, boolean accept) {
            Town town = data.namedTown(name);
            data.answerTown(id(), town, accept);
            return msg(accept ? "town_joined" : "declined", town.name());
        }
        Component answerNation(String name, boolean accept) {
            Nation nation = data.namedNation(name);
            data.answerNation(id(), nation, accept);
            return msg(accept ? "nation_joined" : "declined", nation.name());
        }
        Component townInfo(Town town) {
            Component nation = data.getTownNation(town.id()).map(n -> Component.literal(n.name()))
                    .orElseGet(() -> Component.translatable("historical_nations.independent"));
            return msg("town_info", town.name(), playerName(town.mayor()), data.members(town.id()).size(), nation);
        }
        Component nationInfo(Nation nation) {
            Town capital = data.getTownById(nation.capitalTown()).orElseThrow();
            return msg("nation_info", nation.name(), capital.name(), playerName(capital.mayor()), data.getNationTowns(nation.id()).size());
        }
        Component claimStatus() {
            var dimension = player.serverLevel().dimension().location();
            var position = player.chunkPosition();
            var owner = OpenPacClaims.owner(server(), dimension, position);
            if (owner.isEmpty()) return msg("unclaimed", dimension.toString(), position.x, position.z);
            var identity = owner.get();
            Component affiliation = data.getPlayerTown(identity.id()).<Component>map(t -> Component.literal(t.name()))
                    .orElseGet(() -> msg("none"));
            return msg("claimed", dimension.toString(), position.x, position.z, identity.displayName(), identity.id().toString(), affiliation);
        }
    }
}
