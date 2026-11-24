package dev.zeorez.historicalnations.core;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.zeorez.historicalnations.TerritoryConfig;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Server-side vanilla Gold Ingot settlement cost checks and inventory transactions.
 * Completely independent from the v0.6.0 national currency and banking ledger.
 */
public final class GoldCosts {
    private GoldCosts() {}

    public static int townCreationCost() {
        return TerritoryConfig.ENABLE_GOLD_COSTS.get() ? Math.max(0, TerritoryConfig.TOWN_CREATION_GOLD_COST.get()) : 0;
    }

    public static int nationCreationCost() {
        return TerritoryConfig.ENABLE_GOLD_COSTS.get() ? Math.max(0, TerritoryConfig.NATION_CREATION_GOLD_COST.get()) : 0;
    }

    public static int claimCost() {
        return TerritoryConfig.ENABLE_GOLD_COSTS.get() ? Math.max(0, TerritoryConfig.CLAIM_GOLD_COST.get()) : 0;
    }

    public static int unclaimRefund(int paidGold) {
        if (!TerritoryConfig.ENABLE_GOLD_COSTS.get() || !TerritoryConfig.REFUND_ON_UNCLAIM.get() || paidGold <= 0) {
            return 0;
        }
        return Math.min(paidGold, claimCost());
    }

    public static boolean isVanillaGoldIngot(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.GOLD_INGOT) && (!stack.hasTag() || stack.getTag().isEmpty());
    }

    public static int countGold(ServerPlayer player) {
        requireServerThread(player);
        Inventory inv = player.getInventory();
        long total = 0;
        for (ItemStack stack : inv.items) {
            if (isVanillaGoldIngot(stack)) total += stack.getCount();
        }
        for (ItemStack stack : inv.offhand) {
            if (isVanillaGoldIngot(stack)) total += stack.getCount();
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    public static boolean hasGold(ServerPlayer player, int amount) {
        if (amount <= 0) return true;
        return countGold(player) >= amount;
    }

    public static boolean takeGold(ServerPlayer player, int amount) {
        requireServerThread(player);
        if (amount <= 0) return true;
        if (countGold(player) < amount) return false;
        Inventory inv = player.getInventory();
        int remaining = drainSlots(inv.items, amount);
        remaining = drainSlots(inv.offhand, remaining);
        syncInventory(player);
        if (remaining != 0) {
            int taken = amount - remaining;
            if (taken > 0) giveGold(player, taken);
            return false;
        }
        return true;
    }

    public static void giveGold(ServerPlayer player, int amount) {
        requireServerThread(player);
        if (amount <= 0) return;
        Inventory inv = player.getInventory();
        int remaining = amount;
        int maxStack = new ItemStack(Items.GOLD_INGOT).getMaxStackSize();
        while (remaining > 0) {
            int batch = Math.min(remaining, maxStack);
            ItemStack stack = new ItemStack(Items.GOLD_INGOT, batch);
            if (!inv.add(stack) && !stack.isEmpty()) {
                player.drop(stack, false);
            }
            remaining -= batch;
        }
        syncInventory(player);
    }

    public static CommandSyntaxException insufficientGold(int required, int available) {
        return new SimpleCommandExceptionType(
                Component.translatable("historical_nations.insufficient_gold", required, available)).create();
    }

    private static int drainSlots(NonNullList<ItemStack> slots, int remaining) {
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            ItemStack stack = slots.get(i);
            if (!isVanillaGoldIngot(stack)) continue;
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            if (stack.isEmpty()) {
                slots.set(i, ItemStack.EMPTY);
            }
            remaining -= take;
        }
        return remaining;
    }

    private static void syncInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        if (player.connection != null) {
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static void requireServerThread(ServerPlayer player) {
        if (player.server != null && !player.server.isSameThread()) {
            throw new IllegalStateException("Gold inventory transactions require the logical server thread");
        }
    }
}
