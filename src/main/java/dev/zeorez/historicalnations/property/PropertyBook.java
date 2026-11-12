package dev.zeorez.historicalnations.property;

import dev.zeorez.historicalnations.api.ParcelView;
import dev.zeorez.historicalnations.api.ParcelView.Box;
import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.integration.TownTerritories;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import java.util.*;

public final class PropertyBook {
    static final class Plot {
        final Parcel land;
        final List<Parcel> quarters = new ArrayList<>();
        Plot(Parcel land) { this.land = land; }
        Parcel quarter(BlockPos pos) {
            for (var parcel : quarters)
                for (var box : parcel.boxes) if (box.contains(pos)) return parcel;
            return null;
        }
        boolean intersects(Box box, Parcel except) {
            return quarters.stream().filter(p -> p != except).flatMap(p -> p.boxes.stream()).anyMatch(box::intersects);
        }
    }
    record GroupKey(UUID town, String name) {}
    final Map<GroupKey, Map<String,Boolean>> groups = new HashMap<>();
    final Map<Location, Plot> plots = new HashMap<>();
    private final WorldRegistry registry;
    public PropertyBook(WorldRegistry registry) { this.registry = registry; }

    public void add(Location loc) {
        plots.computeIfAbsent(loc, key -> new Plot(new Parcel(UUID.randomUUID(),
                "Plot " + key.x() + ", " + key.z(), Parcel.Kind.RESIDENTIAL)));
    }
    public void remove(Location loc) { plots.remove(loc); }
    public void disband(UUID town) { groups.keySet().removeIf(g -> g.town.equals(town)); }
    public void depart(UUID town, UUID player) {
        for (Location location : registry.territoryLocations(town)) {
            Plot plot = plots.get(location);
            if (plot == null) continue;
            revoke(plot.land, player);
            plot.quarters.forEach(p -> revoke(p, player));
        }
    }
    private void revoke(Parcel p, UUID player) {
        if (player.equals(p.owner)) p.assign(null);
        p.trusted.remove(player);
    }
    static Location location(ResourceLocation dim, BlockPos pos) {
        return new Location(dim, pos.getX() >> 4, pos.getZ() >> 4);
    }

    Map<String,Boolean> shared(UUID town, Parcel parcel) {
        return groups.getOrDefault(new GroupKey(town, parcel.group), Map.of());
    }

    public static Optional<ParcelView> lookup(MinecraftServer server, ResourceLocation dim, BlockPos pos, boolean quarter) {
        Location loc = location(dim, pos);
        var link = new TownTerritories(server).verified(loc);
        if (link == null) return Optional.empty();
        var book = WorldRegistry.get(server).properties;
        Plot plot = book.plots.get(loc);
        if (plot == null) return Optional.empty();
        Parcel parcel = quarter ? plot.quarter(pos) : plot.land;
        return Optional.ofNullable(parcel).map(p -> p.view(link.town(), loc, book.shared(link.town(), p)));
    }

    public void save(CompoundTag root) {
        var rows = new ListTag();
        plots.forEach((loc, plot) -> {
            var row = plot.land.save();
            row.putUUID("Town", registry.territory(loc).town());
            row.putString("Dimension", loc.dimension().toString());
            row.putInt("X", loc.x());
            row.putInt("Z", loc.z());
            var quarters = new ListTag();
            plot.quarters.forEach(p -> quarters.add(p.save()));
            row.put("Quarters", quarters);
            rows.add(row);
        });
        root.put("Plots", rows);
        var settings = new ListTag();
        groups.forEach((key, rules) -> {
            var row = new CompoundTag();
            row.putUUID("Town", key.town);
            row.putString("Name", key.name);
            var values = new CompoundTag();
            rules.forEach(values::putBoolean);
            row.put("Rules", values);
            settings.add(row);
        });
        root.put("PlotGroups", settings);
    }

    public void load(CompoundTag root) {
        registry.territoryLocations().forEach(this::add);
        for (Tag entry : root.getList("PlotGroups", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) entry;
            if (!row.hasUUID("Town") || registry.getTownById(row.getUUID("Town")).isEmpty() || row.getString("Name").isBlank()) continue;
            var rules = new HashMap<String,Boolean>();
            var values = row.getCompound("Rules");
            values.getAllKeys().forEach(k -> rules.put(k, values.getBoolean(k)));
            groups.put(new GroupKey(row.getUUID("Town"), row.getString("Name")), rules);
        }
        for (Tag entry : root.getList("Plots", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag) entry;
            var dim = ResourceLocation.tryParse(row.getString("Dimension"));
            if (dim == null || !row.contains("X", Tag.TAG_INT) || !row.contains("Z", Tag.TAG_INT)) continue;
            Location loc = new Location(dim, row.getInt("X"), row.getInt("Z"));
            var link = registry.territory(loc);
            if (link == null || !row.hasUUID("Town") || !link.town().equals(row.getUUID("Town"))) continue;
            Plot plot = new Plot(Parcel.read(row));
            if (!plot.land.boxes.isEmpty()) throw new IllegalStateException("Invalid plot geometry at " + loc);
            for (Tag child : row.getList("Quarters", Tag.TAG_COMPOUND)) {
                Parcel q = Parcel.read((CompoundTag)child);
                if (q.boxes.isEmpty() || q.boxes.stream().anyMatch(b -> !b.inChunk(loc.x(), loc.z()) || plot.intersects(b, null)))
                    throw new IllegalStateException("Invalid quarter geometry at " + loc);
                plot.quarters.add(q);
            }
            plots.put(loc, plot);
        }
    }
}
