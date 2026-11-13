package dev.zeorez.hntests;

import net.minecraft.server.MinecraftServer;
import java.time.*;

/** Exercises midnight and rollback without changing the host clock. Test jar only. */
public final class FinanceClockFixture {
    public static void day(MinecraftServer server, int offset) {
        try {
            var settle = dev.zeorez.historicalnations.core.DailyFinance.class.getDeclaredMethod(
                    "settle", MinecraftServer.class, long.class);
            settle.setAccessible(true);
            settle.invoke(null, server, LocalDate.now(ZoneOffset.UTC).plusDays(offset).toEpochDay());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot advance test settlement day", failure);
        }
    }
}
