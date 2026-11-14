package dev.zeorez.historicalnations.core;

import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraftforge.registries.ForgeRegistries;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import static dev.zeorez.historicalnations.core.EconomyCommands.msg;

final class TradeStatistics {
    private record Direction(UUID nation, boolean imports) {}
    private final Map<Direction,Statement> statements = new HashMap<>();
    private static final class Statement {
        BigInteger count = BigInteger.ZERO;
        final Map<String,BigInteger> volume = new TreeMap<>(), duty = new TreeMap<>();
        final Map<UUID,BigInteger> partners = new HashMap<>();
        final Deque<String> recent = new ArrayDeque<>();
    }
    void completed(TradeOffer o) {
        String receipt = Instant.now() + " | " + o.id() + " | " + o.origin() + " → " + o.destination()
                + " | " + o.seller() + " → " + o.buyer()
                + " | " + o.goods().getCount() + " × " + ForgeRegistries.ITEMS.getKey(o.goods().getItem())
                + " | " + o.price() + " + " + o.duty() + " " + o.currency();
        append(o.destination(),true,o.origin(),o,receipt);
        append(o.origin(),false,o.destination(),o,receipt);
    }
    private void append(UUID nation,boolean imports,UUID partner,TradeOffer o,String receipt) {
        var s = statements.computeIfAbsent(new Direction(nation,imports),k -> new Statement());
        s.count = s.count.add(BigInteger.ONE);
        s.volume.merge(o.currency(),BigInteger.valueOf(o.price()),BigInteger::add);
        if (imports && o.duty() > 0) s.duty.merge(o.currency(),BigInteger.valueOf(o.duty()),BigInteger::add);
        s.partners.merge(partner,BigInteger.ONE,BigInteger::add);
        if (s.recent.size() == 16) s.recent.removeFirst();
        s.recent.addLast(receipt);
    }
    Component report(WorldRegistry data, UUID nation, boolean imports, boolean detail) {
        var s = statements.getOrDefault(new Direction(nation,imports),new Statement());
        var partners = s.partners.entrySet().stream().sorted(Map.Entry.<UUID,BigInteger>comparingByValue()
                .reversed().thenComparing(Map.Entry.comparingByKey())).limit(5)
                .map(e -> data.getNationById(e.getKey()).map(n -> n.name()).orElse(e.getKey().toString()) + " × " + e.getValue()).toList();
        var text = msg(imports ? "trade_imports" : "trade_exports", s.count.toString(), amounts(s.volume),
                String.join(", ",partners)).copy();
        if (imports) text.append("\n").append(msg("trade_duties",amounts(s.duty)));
        if (detail) s.recent.descendingIterator().forEachRemaining(line -> text.append("\n"+line));
        return text;
    }
    private static String amounts(Map<String,BigInteger> values) {
        if (values.isEmpty()) return "0";
        return String.join(", ",values.entrySet().stream().map(e -> e.getValue()+" "+e.getKey()).toList());
    }
    private static <K> CompoundTag numbers(Map<K,BigInteger> map) {
        var t = new CompoundTag();
        map.forEach((key,value) -> t.putString(key.toString(),value.toString()));
        return t;
    }
    ListTag save() {
        var result = new ListTag();
        statements.forEach((direction,s) -> {
            var t = new CompoundTag();
            t.putUUID("Nation",direction.nation); t.putBoolean("Imports",direction.imports);
            t.putString("Count",s.count.toString()); t.put("Volume",numbers(s.volume));
            t.put("Duty",numbers(s.duty)); t.put("Partners",numbers(s.partners));
            var recent = new ListTag();
            s.recent.forEach(line -> recent.add(StringTag.valueOf(line)));
            t.put("Recent",recent);
            result.add(t);
        });
        return result;
    }
    private static BigInteger number(CompoundTag t,String key) {
        var value = new BigInteger(t.getString(key));
        if (value.signum() < 0) throw new IllegalArgumentException(key);
        return value;
    }
    private static <K> void loadNumbers(CompoundTag t,String name,Map<K,BigInteger> dest,java.util.function.Function<String,K> parse) {
        if (!(t.get(name) instanceof CompoundTag fields)) throw new IllegalArgumentException(name);
        fields.getAllKeys().forEach(k -> dest.put(parse.apply(k),number(fields,k)));
    }
    void load(ListTag entries) {
        for (Tag entry : entries) {
            var t = (CompoundTag)entry;
            var s = new Statement();
            s.count = number(t,"Count");
            loadNumbers(t,"Volume",s.volume,k -> k);
            loadNumbers(t,"Duty",s.duty,k -> k);
            loadNumbers(t,"Partners",s.partners,UUID::fromString);
            var recent = TradeBook.list(t,"Recent",Tag.TAG_STRING);
            if (recent.size()>16 || !t.contains("Imports",Tag.TAG_BYTE)) throw new IllegalArgumentException("statement");
            recent.forEach(line -> s.recent.add(line.getAsString()));
            if (statements.putIfAbsent(new Direction(t.getUUID("Nation"),t.getBoolean("Imports")),s) != null)
                throw new IllegalArgumentException("duplicate statement");
        }
    }
}
