package dev.zeorez.historicalnations.core;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.time.Instant;
import java.util.*;
import static dev.zeorez.historicalnations.core.WorldRegistry.require;

final class TradeBook {
    final WorldRegistry data;
    private final Map<UUID,TradeOffer> offers = new LinkedHashMap<>();
    private final Map<UUID,Integer> tariffs = new HashMap<>();
    private final Map<UUID,Set<UUID>> bans = new HashMap<>();
    final TradeStatistics stats = new TradeStatistics();
    private Tag unreadable;

    TradeBook(WorldRegistry data) { this.data = data; }
    void available() { require(unreadable == null, "trade_data"); }

    TradeOffer offer(ServerPlayer seller, ServerPlayer buyer, int amount, long price, String currency) {
        available();
        TradeOffer.nearby(seller,buyer);
        UUID origin = data.country(seller.getUUID()).id(), destination = data.country(buyer.getUUID()).id();
        checkBorders(origin,destination);
        var hand = seller.getMainHandItem();
        require(!hand.isEmpty() && amount > 0 && amount <= hand.getCount() && amount <= hand.getMaxStackSize()
                && amount <= 64, "trade_item");
        require(price > 0, "bad_amount");
        expire();
        var offer = new TradeOffer(UUID.randomUUID(), seller.getUUID(),buyer.getUUID(),origin,destination,
                hand.copyWithCount(amount),price,data.ledger.currency(currency),dutyRate(origin,destination),now()+120);
        offer.total();
        require(offers.values().stream().filter(o -> o.seller().equals(offer.seller())).limit(32).count() < 32, "trade_limit");
        offers.put(offer.id(),offer);
        data.setDirty();
        return offer;
    }
    TradeOffer find(UUID id, UUID viewer) {
        available();
        TradeOffer o = offers.get(id);
        require(o != null && (o.buyer().equals(viewer) || o.seller().equals(viewer)), "trade_missing");
        if (o.expires() <= now()) {
            remove(id);
            throw new RuleViolation("trade_expired");
        }
        return o;
    }
    void dismiss(UUID id, UUID actor, boolean deny) {
        var o = find(id,actor);
        require((deny ? o.buyer() : o.seller()).equals(actor), "trade_role");
        remove(id);
    }
    void accept(ServerPlayer buyer, TradeOffer o) {
        require(find(o.id(),buyer.getUUID()) == o && o.buyer().equals(buyer.getUUID()), "trade_role");
        var seller = buyer.server.getPlayerList().getPlayer(o.seller());
        TradeOffer.nearby(seller,buyer);
        require(data.country(seller.getUUID()).id().equals(o.origin()) && data.country(buyer.getUUID()).id().equals(o.destination()),
                "trade_requote");
        checkBorders(o.origin(),o.destination());
        require(dutyRate(o.origin(),o.destination()) == o.tariffBps(), "trade_requote");
        var placement = o.placement(seller,buyer);
        var remainder = seller.getMainHandItem().copyWithCount(seller.getMainHandItem().getCount() - o.goods().getCount());
        data.ledger.settleGoods(o);
        seller.getInventory().items.set(seller.getInventory().selected,remainder.isEmpty() ? ItemStack.EMPTY : remainder);
        placement.forEach(buyer.getInventory().items::set);
        remove(o.id());
        if (!o.origin().equals(o.destination())) stats.completed(o);
        seller.getInventory().setChanged();
        buyer.getInventory().setChanged();
    }
    private void remove(UUID id) { offers.remove(id); data.setDirty(); }
    private static long now() { return Instant.now().getEpochSecond(); }
    void expire() {
        long time = now();
        if (offers.values().removeIf(o -> o.expires() <= time)) data.setDirty();
    }
    int tariff(UUID nation) { available(); return tariffs.getOrDefault(nation,0); }
    private int dutyRate(UUID origin, UUID destination) { return origin.equals(destination) ? 0 : tariff(destination); }
    void tariff(UUID actor,int bps) {
        available();
        UUID nation = data.ledNation(actor).id();
        require(bps >= 0 && bps <= 5000, "trade_rate");
        if (bps == 0) tariffs.remove(nation);
        else tariffs.put(nation,bps);
        data.setDirty();
    }
    Set<UUID> bans(UUID nation) { available(); return bans.getOrDefault(nation,Set.of()); }
    List<UUID> bannedBy(UUID nation) {
        available();
        return bans.entrySet().stream().filter(e -> e.getValue().contains(nation)).map(Map.Entry::getKey).sorted().toList();
    }
    void embargo(UUID actor, UUID target, boolean add) {
        available();
        UUID nation = data.ledNation(actor).id();
        require(!nation.equals(target), "trade_self_ban");
        if (add) bans.computeIfAbsent(nation,k -> new HashSet<>()).add(target);
        else if (bans.containsKey(nation)) bans.get(nation).remove(target);
        data.setDirty();
    }
    private void checkBorders(UUID origin, UUID destination) {
        require(!bans(origin).contains(destination) && !bans(destination).contains(origin), "trade_embargo");
    }
    void disband(UUID nation) {
        available();
        tariffs.remove(nation);
        bans.remove(nation);
        bans.values().forEach(set -> set.remove(nation));
        offers.values().removeIf(o -> o.origin().equals(nation) || o.destination().equals(nation));
        data.setDirty();
    }

    void save(CompoundTag root) {
        if (unreadable != null) { root.put("Trade",unreadable.copy()); return; }
        var t = new CompoundTag();
        t.putInt("Version",1);
        var active = new ListTag();
        offers.values().forEach(o -> active.add(o.save()));
        t.put("Offers",active);
        var policies = new ListTag();
        for (var nation : data.nations()) {
            int rate = tariff(nation.id());
            var targets = bans(nation.id());
            if (rate == 0 && targets.isEmpty()) continue;
            var row = new CompoundTag();
            row.putUUID("Nation",nation.id());
            row.putInt("Tariff",rate);
            var list = new ListTag();
            targets.forEach(id -> list.add(NbtUtils.createUUID(id)));
            row.put("Targets",list);
            policies.add(row);
        }
        t.put("Policies",policies);
        t.put("Stats",stats.save());
        root.put("Trade",t);
    }
    void load(CompoundTag root) {
        var raw = root.get("Trade");
        if (raw == null) return;
        try {
            if (!(raw instanceof CompoundTag t) || t.getInt("Version") != 1) throw new IllegalArgumentException("version");
            for (Tag entry : list(t,"Offers",Tag.TAG_COMPOUND)) {
                var o = TradeOffer.read((CompoundTag)entry);
                if (o.expires() > now() && offers.putIfAbsent(o.id(),o) != null) throw new IllegalArgumentException("duplicate offer");
            }
            for (Tag entry : list(t,"Policies",Tag.TAG_COMPOUND)) {
                var row = (CompoundTag)entry;
                UUID nation = row.getUUID("Nation");
                int rate = row.getInt("Tariff");
                require(row.contains("Tariff",Tag.TAG_INT) && rate >= 0 && rate <= 5000 && !bans.containsKey(nation), "trade_data");
                var targets = new HashSet<UUID>();
                for (Tag target : list(row,"Targets",Tag.TAG_INT_ARRAY)) targets.add(NbtUtils.loadUUID(target));
                bans.put(nation,targets);
                tariffs.put(nation,rate);
            }
            stats.load(list(t,"Stats",Tag.TAG_COMPOUND));
        } catch (RuntimeException ex) {
            unreadable = raw.copy();
            offers.clear();
            LogUtils.getLogger().error("Trade records locked. Restore from a world backup.",ex);
        }
    }
    static ListTag list(CompoundTag t,String name,int type) {
        if (!(t.get(name) instanceof ListTag list) || !list.isEmpty() && list.getElementType() != type)
            throw new IllegalArgumentException(name);
        return list;
    }
}
