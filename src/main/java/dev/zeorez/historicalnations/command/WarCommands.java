package dev.zeorez.historicalnations.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.core.RuleViolation;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.gui.PanelNetwork;
import dev.zeorez.historicalnations.gui.PanelQuery.View;
import dev.zeorez.historicalnations.map.MapModels.FocusKind;
import dev.zeorez.historicalnations.map.MapNetwork;
import dev.zeorez.historicalnations.war.WarModels.ActiveCaptureFlag;
import dev.zeorez.historicalnations.war.WarModels.TruceRecord;
import dev.zeorez.historicalnations.war.WarModels.WarRecord;
import dev.zeorez.historicalnations.war.WarNetwork;
import dev.zeorez.historicalnations.war.WarSchedule;
import dev.zeorez.historicalnations.war.WarService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Player, Nation Leader, and Server Admin commands for v11.0.0 Flag War & Diplomacy (Phase 14).
 */
public final class WarCommands {
    private WarCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var war = literal("war")
                .executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                    if (!PanelNetwork.open(sp, View.WAR_DIPLOMACY, null)) {
                        ctx.getSource().sendSuccess(() -> buildStatusText(ctx.getSource().getServer(), sp), false);
                    }
                    return Command.SINGLE_SUCCESS;
                });

        // /war declare <nation>
        war.then(literal("declare")
                .then(argument("target", StringArgumentType.greedyString())
                        .executes(playerOp((sp, data, ctx) -> {
                            String target = StringArgumentType.getString(ctx, "target");
                            WarRecord rec = WarService.declareWar(sp, target);
                            return Component.translatable("historical_nations.war_declared_confirm",
                                    WarService.warTitle(data, rec), rec.warId.toString().substring(0, 8));
                        }))));

        // /war join <allyNation>
        war.then(literal("join")
                .then(argument("nation", StringArgumentType.greedyString())
                        .executes(playerOp((sp, data, ctx) -> {
                            String ally = StringArgumentType.getString(ctx, "nation");
                            WarRecord rec = WarService.joinWar(sp, ally);
                            return Component.translatable("historical_nations.war_joined_confirm",
                                    WarService.warTitle(data, rec));
                        }))));

        // /war info [nation]
        war.then(literal("info")
                .executes(playerOp((sp, data, ctx) -> {
                    Nation own = data.getPlayerNation(sp.getUUID()).orElse(null);
                    return buildWarInfoForNation(data, own);
                }))
                .then(argument("nation", StringArgumentType.greedyString())
                        .executes(playerOp((sp, data, ctx) -> {
                            Nation n = data.namedNation(StringArgumentType.getString(ctx, "nation"));
                            return buildWarInfoForNation(data, n);
                        }))));

        // /war list
        war.then(literal("list").executes(ctx -> {
            WorldRegistry data = WorldRegistry.get(ctx.getSource().getServer());
            List<WarRecord> ongoing = data.wars.ongoingWars();
            if (ongoing.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component.translatable("historical_nations.war_none_active"), false);
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ongoing.size(); i++) {
                WarRecord w = ongoing.get(i);
                if (i > 0) sb.append(" | ");
                sb.append(WarService.warTitle(data, w))
                        .append(" [").append(w.state.name())
                        .append(", occ=").append(w.occupiedChunks.size())
                        .append(", id=").append(w.warId.toString(), 0, 8).append("]");
            }
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        }));

        // /war status
        war.then(literal("status").executes(ctx -> {
            ServerPlayer sp = ctx.getSource().getEntity() instanceof ServerPlayer p ? p : null;
            ctx.getSource().sendSuccess(() -> buildStatusText(ctx.getSource().getServer(), sp), false);
            return 1;
        }));

        // /war map -> opens Political World Map directly in WAR MAP MODE
        war.then(literal("map").executes(ctx -> {
            ServerPlayer sp = ctx.getSource().getPlayerOrException();
            if (!TerritoryConfig.MAP_ENABLED.get()) {
                ctx.getSource().sendFailure(Component.translatable("historical_nations.map_disabled"));
                return 0;
            }
            if (!MapNetwork.requestClientOpenWarMode(sp)) {
                ctx.getSource().sendFailure(Component.translatable("historical_nations.map_client_required"));
                return 0;
            }
            return 1;
        }));

        // /war attacks
        war.then(literal("attacks").executes(ctx -> {
            WorldRegistry data = WorldRegistry.get(ctx.getSource().getServer());
            var flags = data.wars.activeFlags();
            if (flags.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component.translatable("historical_nations.war_no_active_flags"), false);
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            int idx = 0;
            for (ActiveCaptureFlag f : flags) {
                if (idx++ > 0) sb.append(" | ");
                String att = data.getNationById(f.attackingNationId).map(Nation::name).orElse("?");
                String town = data.getTownById(f.defendingTownId).map(Town::name).orElse("?");
                sb.append(String.format(Locale.ROOT, "%s -> %s [%d,%d] %d%% (%ds, %s)",
                        att, town, f.chunk.x(), f.chunk.z(), f.progressPercent(), f.remainingSeconds(), f.runState.name()));
            }
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        }));

        // /war truce
        war.then(literal("truce").executes(playerOp((sp, data, ctx) -> {
            Nation own = data.country(sp.getUUID());
            long nowMs = System.currentTimeMillis();
            List<TruceRecord> truces = data.wars.activeTrucesFor(own.id(), nowMs);
            if (truces.isEmpty()) {
                return Component.translatable("historical_nations.war_no_truces");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < truces.size(); i++) {
                TruceRecord tr = truces.get(i);
                UUID otherId = tr.pair().other(own.id());
                String otherName = data.getNationById(otherId).map(Nation::name).orElse("?");
                long minsLeft = Math.max(1L, (tr.expiresAtEpochMs() - nowMs) / 60_000L);
                if (i > 0) sb.append(", ");
                sb.append(otherName).append(" (").append(minsLeft / 60).append("h ").append(minsLeft % 60).append("m)");
            }
            return Component.translatable("historical_nations.war_truces_list", sb.toString());
        })));

        // /war peace <nation>  (opens Peace Treaty GUI; also supports CLI subcommands annexall/releaseall/approve for scripting & headless ops)
        var peace = literal("peace");
        peace.then(literal("annexall").then(argument("nation", StringArgumentType.greedyString())
                .executes(playerOp((sp, data, ctx) -> {
                    WarRecord w = resolveWarWithTarget(data, sp, StringArgumentType.getString(ctx, "nation"));
                    WarService.setTreatyBulkMode(sp, w.warId, true);
                    return Component.translatable("historical_nations.updated");
                }))));
        peace.then(literal("releaseall").then(argument("nation", StringArgumentType.greedyString())
                .executes(playerOp((sp, data, ctx) -> {
                    WarRecord w = resolveWarWithTarget(data, sp, StringArgumentType.getString(ctx, "nation"));
                    WarService.setTreatyBulkMode(sp, w.warId, false);
                    return Component.translatable("historical_nations.updated");
                }))));
        peace.then(literal("approve").then(argument("nation", StringArgumentType.greedyString())
                .executes(playerOp((sp, data, ctx) -> {
                    WarRecord w = resolveWarWithTarget(data, sp, StringArgumentType.getString(ctx, "nation"));
                    boolean concluded = WarService.approvePeaceTreaty(sp, w.warId, w.peaceDraft.version, true);
                    return Component.translatable(concluded ? "historical_nations.war_treaty_ratified" : "historical_nations.war_treaty_waiting_other");
                }))));
        peace.then(argument("nation", StringArgumentType.greedyString())
                .executes(playerOp((sp, data, ctx) -> {
                    WarRecord w = resolveWarWithTarget(data, sp, StringArgumentType.getString(ctx, "nation"));
                    if (WarNetwork.openPeaceScreen(sp, w.warId)) {
                        return null;
                    }
                    return Component.translatable("historical_nations.war_peace_cli_hint",
                            WarService.warTitle(data, w), w.peaceDraft.version, w.occupiedChunks.size());
                })));
        war.then(peace);

        // /war admin <enable|disable|stop|resume|liquidate|inspect|window>
        var admin = literal("admin").requires(s -> s.hasPermission(2));
        admin.then(literal("enable").executes(ctx -> {
            WarService.adminSetEnabled(ctx.getSource().getServer(), true);
            ctx.getSource().sendSuccess(() -> Component.translatable("historical_nations.war_admin_enabled"), true);
            return 1;
        }));
        admin.then(literal("disable").executes(ctx -> {
            WarService.adminSetEnabled(ctx.getSource().getServer(), false);
            ctx.getSource().sendSuccess(() -> Component.translatable("historical_nations.war_admin_disabled"), true);
            return 1;
        }));
        admin.then(literal("stop").then(argument("warId", StringArgumentType.word()).executes(ctx -> {
            MinecraftServer server = ctx.getSource().getServer();
            WarRecord w = resolveWarByIdPrefix(WorldRegistry.get(server), StringArgumentType.getString(ctx, "warId"));
            WarService.adminStopWar(server, w.warId);
            ctx.getSource().sendSuccess(() -> Component.translatable("historical_nations.war_admin_stopped",
                    w.warId.toString().substring(0, 8), w.occupiedChunks.size()), true);
            return 1;
        })));
        admin.then(literal("resume").then(argument("warId", StringArgumentType.word()).executes(ctx -> {
            MinecraftServer server = ctx.getSource().getServer();
            WarRecord w = resolveWarByIdPrefix(WorldRegistry.get(server), StringArgumentType.getString(ctx, "warId"));
            WarService.adminResumeWar(server, w.warId);
            ctx.getSource().sendSuccess(() -> Component.translatable("historical_nations.updated"), true);
            return 1;
        })));
        admin.then(literal("liquidate").then(argument("warId", StringArgumentType.word()).executes(ctx -> {
            MinecraftServer server = ctx.getSource().getServer();
            WarRecord w = resolveWarByIdPrefix(WorldRegistry.get(server), StringArgumentType.getString(ctx, "warId"));
            WarService.adminLiquidateWar(server, w.warId);
            ctx.getSource().sendSuccess(() -> Component.translatable("historical_nations.war_admin_liquidated",
                    w.warId.toString().substring(0, 8)), true);
            return 1;
        })));
        admin.then(literal("inspect").then(argument("warId", StringArgumentType.word()).executes(ctx -> {
            WorldRegistry data = WorldRegistry.get(ctx.getSource().getServer());
            WarRecord w = resolveWarByIdPrefix(data, StringArgumentType.getString(ctx, "warId"));
            String info = String.format(Locale.ROOT,
                    "War %s | %s | state=%s | attackers=%d | defenders=%d | occupied=%d | activeFlags=%d | treatyVer=%d (att=%s, def=%s) | flagsPlaced=%d defended=%d annexed=%d",
                    w.warId, WarService.warTitle(data, w), w.state.name(),
                    w.attackerNations.size(), w.defenderNations.size(),
                    w.occupiedChunks.size(), data.wars.activeFlagsOfWar(w.warId).size(),
                    w.peaceDraft.version, w.peaceDraft.attackerApproved, w.peaceDraft.defenderApproved,
                    w.stats.flagsPlaced, w.stats.flagsDefended, w.stats.chunksAnnexed);
            ctx.getSource().sendSuccess(() -> Component.literal(info), false);
            return 1;
        })));
        admin.then(literal("window")
                .then(literal("auto").executes(ctx -> setWindowMode(ctx.getSource(), WarSchedule.OverrideMode.AUTO)))
                .then(literal("open").executes(ctx -> setWindowMode(ctx.getSource(), WarSchedule.OverrideMode.FORCE_OPEN)))
                .then(literal("closed").executes(ctx -> setWindowMode(ctx.getSource(), WarSchedule.OverrideMode.FORCE_CLOSED))));
        war.then(admin);

        dispatcher.register(war);
    }

    private static int setWindowMode(CommandSourceStack source, WarSchedule.OverrideMode mode) {
        WarSchedule.setOverrideMode(mode);
        source.sendSuccess(() -> Component.literal("War window override: " + mode + " -> " + WarSchedule.describeStatus(Instant.now())), true);
        return 1;
    }

    private static Component buildStatusText(MinecraftServer server, ServerPlayer viewer) {
        WorldRegistry data = WorldRegistry.get(server);
        boolean enabled = WarService.isWarSystemEnabled(data);
        String win = WarSchedule.describeStatus(Instant.now());
        int ongoing = data.wars.ongoingWars().size();
        int flags = data.wars.activeFlags().size();
        return Component.translatable("historical_nations.war_status_line",
                enabled ? "ENABLED" : "DISABLED", win, ongoing, flags);
    }

    private static Component buildWarInfoForNation(WorldRegistry data, Nation nation) {
        if (nation == null) throw new RuleViolation("no_nation");
        List<WarRecord> list = data.wars.ongoingWarsOfNation(nation.id());
        if (list.isEmpty()) {
            return Component.translatable("historical_nations.war_nation_peaceful", nation.name());
        }
        StringBuilder sb = new StringBuilder();
        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            WarRecord w = list.get(i);
            if (i > 0) sb.append(" || ");
            String prep = w.state == dev.zeorez.historicalnations.war.WarModels.WarState.PREPARING && w.preparationEndsAtEpochMs > nowMs
                    ? (" (" + ((w.preparationEndsAtEpochMs - nowMs) / 60_000L) + "m prep)")
                    : "";
            sb.append(WarService.warTitle(data, w)).append(" [").append(w.state.name()).append(prep)
                    .append(", occupied=").append(w.occupiedChunks.size()).append("]");
        }
        return Component.literal(sb.toString());
    }

    public static WarRecord resolveWarWithTarget(WorldRegistry data, ServerPlayer actor, String targetName) {
        Nation own = data.country(actor.getUUID());
        Nation targetNation = data.nations().stream()
                .filter(n -> n.name().equalsIgnoreCase(targetName.strip()))
                .findFirst().orElse(null);
        if (targetNation != null) {
            return data.wars.findOngoingWarBetween(own.id(), targetNation.id(), false)
                    .orElseThrow(() -> new RuleViolation("no_active_war"));
        }
        Town targetTown = data.namedTown(targetName);
        return data.wars.findOngoingWarBetween(own.id(), targetTown.id(), true)
                .orElseThrow(() -> new RuleViolation("no_active_war"));
    }

    private static WarRecord resolveWarByIdPrefix(WorldRegistry data, String prefixOrUuid) {
        String clean = prefixOrUuid.strip().toLowerCase(Locale.ROOT);
        try {
            UUID exact = UUID.fromString(clean);
            return data.wars.warById(exact).orElseThrow(() -> new SimpleCommandExceptionType(
                    Component.translatable("historical_nations.no_active_war")).create());
        } catch (Exception ignored) {
        }
        for (WarRecord w : data.wars.allWars()) {
            if (w.warId.toString().toLowerCase(Locale.ROOT).startsWith(clean)) {
                return w;
            }
        }
        throw new RuleViolation("no_active_war");
    }

    private interface PlayerOp {
        Component run(ServerPlayer player, WorldRegistry data, com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    private static Command<CommandSourceStack> playerOp(PlayerOp op) {
        return ctx -> {
            ServerPlayer sp = ctx.getSource().getPlayerOrException();
            WorldRegistry data = WorldRegistry.get(ctx.getSource().getServer());
            try {
                Component res = op.run(sp, data, ctx);
                if (res != null) ctx.getSource().sendSuccess(() -> res, false);
                return Command.SINGLE_SUCCESS;
            } catch (RuleViolation rv) {
                throw new SimpleCommandExceptionType(
                        Component.translatable("historical_nations." + rv.getMessage())).create();
            }
        };
    }
}
