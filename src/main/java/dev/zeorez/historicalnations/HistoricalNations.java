package dev.zeorez.historicalnations;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import dev.zeorez.historicalnations.command.NationCommands;

@Mod(HistoricalNations.ID)
public final class HistoricalNations {
    public static final String ID = "historical_nations";

    public HistoricalNations() {
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void registerCommands(RegisterCommandsEvent event) {
        NationCommands.register(event.getDispatcher());
    }
}
