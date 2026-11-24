package dev.zeorez.historicalnations;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import dev.zeorez.historicalnations.command.NationCommands;
import dev.zeorez.historicalnations.integration.TerritoryHooks;
import dev.zeorez.historicalnations.integration.TownTerritories;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

@Mod(HistoricalNations.ID)
public final class HistoricalNations {
    public static final String ID = "historical_nations";

    public HistoricalNations() {
        dev.zeorez.historicalnations.gui.PanelNetwork.register();
        dev.zeorez.historicalnations.map.MapNetwork.register();
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> dev.zeorez.historicalnations.client.MapClientInit::initClientNetwork);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, TerritoryConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(dev.zeorez.historicalnations.property.QuarterCommands.class);
        MinecraftForge.EVENT_BUS.register(dev.zeorez.historicalnations.property.ParcelProtection.class);
        MinecraftForge.EVENT_BUS.addListener(dev.zeorez.historicalnations.core.DailyFinance::tick);
        MinecraftForge.EVENT_BUS.addListener(dev.zeorez.historicalnations.core.TradeCommands::tick);
        MinecraftForge.EVENT_BUS.addListener(dev.zeorez.historicalnations.core.DailyFinance::login);
        MinecraftForge.EVENT_BUS.addListener(dev.zeorez.historicalnations.map.PoliticalMapServer::tick);
        MinecraftForge.EVENT_BUS.addListener(dev.zeorez.historicalnations.map.PoliticalMapServer::onPlayerLogout);
        MinecraftForge.EVENT_BUS.addListener(dev.zeorez.historicalnations.map.PoliticalMapServer::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(TerritoryHooks::register);
        MinecraftForge.EVENT_BUS.addListener(this::serverStarted);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void serverStarted(ServerStartedEvent event) {
        new TownTerritories(event.getServer()).reconcile();
    }

    private void registerCommands(RegisterCommandsEvent event) {
        NationCommands.register(event.getDispatcher());
        dev.zeorez.historicalnations.core.TradeCommands.register(event.getDispatcher());
        dev.zeorez.historicalnations.core.EconomyCommands.register(event.getDispatcher());
        dev.zeorez.historicalnations.property.PropertyCommands.register(event.getDispatcher());
        var dispatcher = event.getDispatcher();
        dispatcher.register(net.minecraft.commands.Commands.literal("t").redirect(dispatcher.getRoot().getChild("town")));
        dispatcher.register(net.minecraft.commands.Commands.literal("n").redirect(dispatcher.getRoot().getChild("nation")));
    }
}
