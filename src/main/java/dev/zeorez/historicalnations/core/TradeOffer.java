package dev.zeorez.historicalnations.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import static dev.zeorez.historicalnations.core.WorldRegistry.require;

/** The price is for the entire stack. The quoted item includes its Forge capabilities and NBT. */
record TradeOffer(UUID id, UUID seller, UUID buyer, UUID origin, UUID destination, ItemStack goods,
                  long price, String currency, int tariffBps, long expires) {
    static void nearby(ServerPlayer seller, ServerPlayer buyer) {
        require(seller != null && buyer != null && !seller.getUUID().equals(buyer.getUUID())
                && seller.isAlive() && buyer.isAlive() && !seller.isSpectator() && !buyer.isSpectator()
                && seller.serverLevel() == buyer.serverLevel() && seller.distanceToSqr(buyer) <= 64, "trade_distance");
    }
    long duty() {
        // Round up to the next whole unit. Splitting an order cannot lower the duty.
        return price / 10000 * tariffBps + (price % 10000 * tariffBps + 9999) / 10000;
    }
    long total() {
        long duty = duty();
        require(price <= Long.MAX_VALUE - duty, "balance_limit");
        return price + duty;
    }
    Component quote() {
        return EconomyCommands.msg("trade_quote", id.toString(), goods.getCount(), goods.getDisplayName(), price,
                duty(), total(), currency);
    }
    /** Prepare every destination stack before changing either player's live inventory. */
    Map<Integer,ItemStack> placement(ServerPlayer sellerPlayer, ServerPlayer buyerPlayer) {
        nearby(sellerPlayer,buyerPlayer);
        ItemStack hand = sellerPlayer.getMainHandItem();
        require(ItemStack.isSameItemSameTags(goods,hand) && hand.getCount() >= goods.getCount(), "trade_item");
        Inventory inventory = buyerPlayer.getInventory();
        var changes = new LinkedHashMap<Integer,ItemStack>();
        int left = goods.getCount();
        for (boolean empty : List.of(false,true)) {
            for (int slot = 0; slot < inventory.items.size() && left > 0; slot++) {
                ItemStack current = inventory.items.get(slot);
                if (current.isEmpty() != empty || !empty && !ItemStack.isSameItemSameTags(goods,current)) continue;
                int limit = Math.min(inventory.getMaxStackSize(), goods.getMaxStackSize());
                int count = empty ? 0 : current.getCount();
                int moved = Math.min(left,limit - count);
                if (moved <= 0) continue;
                changes.put(slot,(empty ? goods : current).copyWithCount(count + moved));
                left -= moved;
            }
        }
        require(left == 0, "trade_full");
        return changes;
    }
    CompoundTag save() {
        var t = new CompoundTag();
        t.putUUID("Id",id); t.putUUID("Seller",seller); t.putUUID("Buyer",buyer);
        t.putUUID("Origin",origin); t.putUUID("Destination",destination);
        t.put("Goods",goods.save(new CompoundTag())); t.putLong("Price",price);
        t.putString("Currency",currency); t.putInt("Tariff",tariffBps); t.putLong("Expires",expires);
        return t;
    }
    static TradeOffer read(CompoundTag t) {
        var o = new TradeOffer(t.getUUID("Id"),t.getUUID("Seller"),t.getUUID("Buyer"),t.getUUID("Origin"),
                t.getUUID("Destination"),ItemStack.of(t.getCompound("Goods")),t.getLong("Price"),t.getString("Currency"),
                t.getInt("Tariff"),t.getLong("Expires"));
        require(!o.goods.isEmpty() && o.goods.getCount() <= o.goods.getMaxStackSize() && o.price > 0
                && o.tariffBps >= 0 && o.tariffBps <= 5000 && !o.seller.equals(o.buyer)
                && o.currency.matches("[A-Z]{3,6}") && t.contains("Tariff",3) && o.expires > 0, "trade_data");
        o.total();
        return o;
    }
}
