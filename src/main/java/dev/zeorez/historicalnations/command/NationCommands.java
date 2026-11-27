package dev.zeorez.historicalnations.command;

import com.mojang.authlib.GameProfile;
import dev.zeorez.historicalnations.gui.PanelNetwork;
import dev.zeorez.historicalnations.gui.PanelQuery.View;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.core.GoldCosts;
import dev.zeorez.historicalnations.core.RuleViolation;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.integration.OpenPacClaims;
import dev.zeorez.historicalnations.integration.TownTerritories;
import dev.zeorez.historicalnations.map.AnvilTileGenerator;
import dev.zeorez.historicalnations.map.MapCalibration;
import dev.zeorez.historicalnations.map.MapModels.FocusKind;
import dev.zeorez.historicalnations.map.MapNetwork;
import dev.zeorez.historicalnations.map.NationPalette;
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
        dispatcher.register(literal("hnc").executes(run(c ->
                PanelNetwork.open(c.player, View.HOME, null) ? null : msg("panel_help"))));

        dispatcher.register(literal("hnc").then(literal("admin").requires(s -> s.hasPermission(4))
                .then(literal("cities").executes(run(c -> dev.zeorez.historicalnations.gui.CityNetwork.open(c.player)
                        ? null : msg("panel_help"))))));
        var mapCmd = literal("map").executes(run(c -> {
            if (!TerritoryConfig.MAP_ENABLED.get()) return msg("map_disabled");
            return MapNetwork.requestClientOpen(c.player, FocusKind.PLAYER, null, 0, 0) ? null : msg("map_client_required");
        }));
        var mapAdmin = literal("admin").requires(src -> src.hasPermission(2));
        mapAdmin.then(literal("status").executes(ctx -> {
            var server = ctx.getSource().getServer();
            var cal = MapCalibration.fromServer(server);
            var st = AnvilTileGenerator.status();
            var root = MapCalibration.resolveTileRoot(server);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "MapTiles=" + root + " | scale=1:" + cal.scale() + " | calibrated=" + cal.calibrated()
                            + " | renderRunning=" + st.running() + " (" + st.processedRegions() + "/" + st.totalRegions() + ") " + st.message()), false);
            return 1;
        }));
        mapAdmin.then(literal("render").executes(ctx -> startRenderCmd(ctx.getSource(), 0))
                .then(argument("radiusRegions", IntegerArgumentType.integer(0, 500))
                        .executes(ctx -> startRenderCmd(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radiusRegions")))));
        mapCmd.then(mapAdmin);
        dispatcher.register(mapCmd);

        var town = literal("town");
        town.then(named("create", (c, name) -> msg("town_created", c.land().createTown(c.player, name).name())));
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
                .executes(run(c -> PanelNetwork.open(c.player, View.TOWN, null) ? null : c.townInfo(c.data.home(c.id())))));
        town.then(literal("list").executes(run(c -> PanelNetwork.open(c.player, View.TOWNS, null) ? null : msg("towns", names(c.data.towns().stream().map(Town::name).toList())))));
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
        town.then(literal("deputy").then(literal("set").then(argument("player", GameProfileArgument.gameProfile()).executes(run(c -> {
            c.data.appoint(c.id(), c.target().getId());
            return msg("updated");
        })))).then(literal("remove").executes(run(c -> {
            c.data.appoint(c.id(), null);
            return msg("updated");
        }))));
        town.then(literal("kick").then(argument("player", GameProfileArgument.gameProfile()).executes(run(c -> {
            c.data.kick(c.id(), c.target().getId());
            return msg("updated");
        }))));
        town.then(literal("description").then(named("set", (c, value) -> {
            c.data.describe(c.id(), value);
            return msg("updated");
        })).then(literal("clear").executes(run(c -> {
            c.data.describe(c.id(), "");
            return msg("updated");
        }))));
        town.then(named("rename", (c, value) -> {
            c.data.renameTown(c.id(), value);
            return msg("updated");
        }));
        town.then(named("spawn", (c, name) -> {
            Town target = c.data.namedTown(name);
            c.land().teleportToTownSpawn(c.player, target, c.command.getSource().hasPermission(2));
            return msg("town_spawned", target.name());
        }).executes(run(c -> {
            Town home = c.data.home(c.id());
            c.land().teleportToTownSpawn(c.player, home, c.command.getSource().hasPermission(2));
            return msg("town_spawned", home.name());
        })));
        Action setTownSpawnAction = c -> {
            var sp = c.land().setTownSpawn(c.player);
            var pos = sp.blockPos();
            return msg("town_spawn_set", c.data.home(c.id()).name(), pos.getX(), pos.getY(), pos.getZ());
        };
        town.then(literal("setspawn").executes(run(setTownSpawnAction)));
        town.then(literal("set").then(literal("spawn").executes(run(setTownSpawnAction))));
        dispatcher.register(town);

        var nation = literal("nation");
        nation.then(named("create", (c, name) -> msg("nation_created", c.createNation(name).name())));
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
                .executes(run(c -> PanelNetwork.open(c.player, View.NATION, null) ? null : c.nationInfo(c.data.country(c.id())))));
        nation.then(literal("list").executes(run(c -> PanelNetwork.open(c.player, View.NATIONS, null) ? null : msg("nations", names(c.data.nations().stream().map(Nation::name).toList())))));
        nation.then(literal("towns").executes(run(c -> msg("towns", names(c.data.getNationTowns(c.data.country(c.id()).id())
                .stream().map(Town::name).toList())))));
        nation.then(literal("disband").executes(run(c -> {
            c.data.disbandNation(c.id());
            return msg("nation_disbanded");
        })));
        nation.then(named("rename", (c, value) -> {
            c.data.renameNation(c.id(), value);
            return msg("updated");
        }));
        nation.then(named("setcapital", (c, value) -> {
            c.data.setCapital(c.id(), value);
            return msg("updated");
        }));
        nation.then(named("color", (c, value) -> {
            c.data.setNationColor(c.id(), NationPalette.parseHex(value));
            return msg("updated");
        }));
        nation.then(named("spawn", (c, name) -> {
            Nation target = c.data.namedNation(name);
            c.land().teleportToNationSpawn(c.player, target, c.command.getSource().hasPermission(2));
            return msg("nation_spawned", target.name());
        }).executes(run(c -> {
            Nation country = c.data.country(c.id());
            c.land().teleportToNationSpawn(c.player, country, c.command.getSource().hasPermission(2));
            return msg("nation_spawned", country.name());
        })));
        Action setNationSpawnAction = c -> {
            var sp = c.land().setNationSpawn(c.player);
            var pos = sp.blockPos();
            return msg("nation_spawn_set", c.data.country(c.id()).name(), pos.getX(), pos.getY(), pos.getZ());
        };
        nation.then(literal("setspawn").executes(run(setNationSpawnAction)));
        nation.then(literal("set").then(literal("spawn").executes(run(setNationSpawnAction))));
        nation.then(named("ally", (c, name) -> {
            Nation target = c.data.namedNation(name);
            boolean formed = c.data.allyNation(c.id(), target);
            UUID targetLeader = c.data.getTownById(target.capitalTown()).map(Town::mayor).orElse(null);
            Nation own = c.data.country(c.id());
            if (formed) {
                if (targetLeader != null) c.notifyPlayer(targetLeader, msg("alliance_formed", own.name()));
                return msg("alliance_formed", target.name());
            } else {
                if (targetLeader != null) c.notifyPlayer(targetLeader, msg("alliance_invite_received", own.name()));
                return msg("alliance_invite_sent", target.name());
            }
        }));
        nation.then(named("unally", (c, name) -> {
            Nation target = c.data.namedNation(name);
            Nation own = c.data.country(c.id());
            c.data.unallyNation(c.id(), target);
            UUID targetLeader = c.data.getTownById(target.capitalTown()).map(Town::mayor).orElse(null);
            if (targetLeader != null) c.notifyPlayer(targetLeader, msg("alliance_broken", own.name()));
            return msg("alliance_broken", target.name());
        }));
        dispatcher.register(nation);
    }

    private static int startRenderCmd(CommandSourceStack source, int radius) {
        var server = source.getServer();
        var root = MapCalibration.resolveTileRoot(server);
        boolean started = AnvilTileGenerator.startBackgroundRender(server, root, radius);
        source.sendSuccess(() -> msg(started ? "map_render_started" : "map_render_busy", root.toString()), true);
        return started ? 1 : 0;
    }

    interface Action {
        Component execute(Context context) throws CommandSyntaxException;
    }
    private interface NamedAction {
        Component execute(Context context, String name) throws CommandSyntaxException;
    }
    private static LiteralArgumentBuilder<CommandSourceStack> named(String verb, NamedAction action) {
        return literal(verb).then(argument("name", StringArgumentType.greedyString()).executes(
                run(c -> action.execute(c, StringArgumentType.getString(c.command, "name")))));
    }
    static Command<CommandSourceStack> run(Action action) {
        return command -> {
            var player = command.getSource().getPlayerOrException();
            var context = new Context(command, player, WorldRegistry.get(command.getSource().getServer()));
            try {
                Component result = action.execute(context);
                if (result != null) command.getSource().sendSuccess(() -> result, false);
                return Command.SINGLE_SUCCESS;
            } catch (RuleViolation error) {
                throw new SimpleCommandExceptionType(msg(error.getMessage())).create();
            }
        };
    }
    static Component msg(String key, Object... args) {
        return Component.translatable("historical_nations." + key, args);
    }
    private static Component names(List<String> names) {
        return names.isEmpty() ? msg("none") : Component.literal(String.join(", ", names));
    }

    record Context(CommandContext<CommandSourceStack> command, ServerPlayer player, WorldRegistry data) {
        UUID id() { return player.getUUID(); }
        MinecraftServer server() { return command.getSource().getServer(); }
        TownTerritories land() { return new TownTerritories(server()); }

        Nation createNation(String name) throws CommandSyntaxException {
            data.checkCreateNation(id(), name);
            int cost = GoldCosts.nationCreationCost();
            if (cost > 0 && !GoldCosts.hasGold(player, cost)) {
                throw GoldCosts.insufficientGold(cost, GoldCosts.countGold(player));
            }
            if (cost > 0 && !GoldCosts.takeGold(player, cost)) {
                throw GoldCosts.insufficientGold(cost, GoldCosts.countGold(player));
            }
            try {
                Nation created = data.createNation(id(), name);
                land().initDefaultNationSpawn(player, created);
                return created;
            } catch (RuntimeException ex) {
                if (cost > 0) GoldCosts.giveGold(player, cost);
                throw ex;
            }
        }

        GameProfile target() throws CommandSyntaxException {
            // The residents screen identifies an offline member by the UUID stored in the town registry.
            var nodes = command.getNodes();
            String input = nodes.get(nodes.size() - 1).getRange().get(command.getInput());
            if (input.length() == 36) {
                try {
                    UUID id = UUID.fromString(input);
                    var cached = server().getProfileCache().get(id);
                    if (cached.isPresent()) return cached.get();
                    if (data.getPlayerTown(id).isPresent()) return new GameProfile(id, id.toString());
                } catch (IllegalArgumentException ignored) {
                    // Let Brigadier handle names and selectors as usual.
                }
            }
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
            if (PanelNetwork.open(player, View.TOWN, town.id())) return null;
            Component nation = data.getTownNation(town.id()).map(n -> Component.literal(n.name()))
                    .orElseGet(() -> Component.translatable("historical_nations.independent"));
            UUID deputy = data.deputy(town.id());
            return msg("town_info", town.name(), playerName(town.mayor()), data.members(town.id()).size(), nation,
                    deputy == null ? msg("none") : Component.literal(playerName(deputy)), data.description(town.id()));
        }
        Component nationInfo(Nation nation) {
            if (PanelNetwork.open(player, View.NATION, nation.id())) return null;
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
