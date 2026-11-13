package dev.zeorez.historicalnations.api;

import dev.zeorez.historicalnations.core.Banking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Logical server thread only. Amounts are whole currency units. Rejections throw RuleViolation. */
public interface EconomyApi {
    enum Bank { TOWN, NATION }
    static EconomyApi get(MinecraftServer server) { return Banking.get(server); }

    long getBalance(UUID player);
    long getTownBalance(UUID town);
    long getNationBalance(UUID nation);
    void transfer(ServerPlayer payer, UUID recipient, long amount);
    void deposit(ServerPlayer member, Bank bank, long amount);
    void withdraw(ServerPlayer leader, Bank bank, long amount);
}
