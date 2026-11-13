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
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, TerritoryConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(dev.zeorez.historicalnations.property.QuarterCommands.class);
        MinecraftForge.EVENT_BUS.register(dev.zeorez.historicalnations.property.ParcelProtection.class);
        MinecraftForge.EVENT_BUS.addListener(TerritoryHooks::register);
        MinecraftForge.EVENT_BUS.addListener(this::serverStarted);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void serverStarted(ServerStartedEvent event) {
        new TownTerritories(event.getServer()).reconcile();
    }

    private void registerCommands(RegisterCommandsEvent event) {
        NationCommands.register(event.getDispatcher());
        dev.zeorez.historicalnations.property.PropertyCommands.register(event.getDispatcher());
    }
}
