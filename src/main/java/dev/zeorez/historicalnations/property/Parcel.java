package dev.zeorez.historicalnations.property;

import dev.zeorez.historicalnations.api.ParcelView;
import dev.zeorez.historicalnations.api.ParcelView.Box;
import dev.zeorez.historicalnations.core.Territory.Location;
import net.minecraft.nbt.*;
import java.util.*;

final class Parcel {
    enum Action { BUILD, DESTROY, INTERACT, ITEM_USE }
    enum Group { TRUSTED, CITIZEN, OUTSIDER }
    enum Kind { RESIDENTIAL, SHOP, FARM, INDUSTRIAL, EMBASSY, INN, PUBLIC, ARENA, WILDS, BANK, JAIL, APARTMENT, STATION }
    static final Set<Kind> QUARTER_TYPES = EnumSet.of(Kind.APARTMENT, Kind.SHOP, Kind.INN, Kind.STATION, Kind.EMBASSY);
    final UUID id;
    final Set<UUID> trusted = new HashSet<>();
    final Map<String, Boolean> permissions = new HashMap<>();
    final List<Box> boxes = new ArrayList<>();
    UUID owner;
    Long price;
    String name, description = "", group = "";
    Kind kind;
    int color = 0x46b8cf;

    Parcel(UUID id, String name, Kind kind) {
        this.id = id;
        this.name = name;
        this.kind = kind;
    }

    void assign(UUID next) {
        owner = next;
        price = null;
        trusted.clear();
        permissions.clear();
    }

    ParcelView view(UUID town, Location loc, Map<String,Boolean> shared) {
        var effective = new HashMap<>(permissions);
        effective.putAll(shared);
        return new ParcelView(id, town, loc.dimension(), loc.x(), loc.z(), name, kind.name(), owner, price,
                group, color, description, trusted, effective, boxes);
    }

    CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putString("Kind", kind.name());
        tag.putString("Group", group);
        tag.putString("Description", description);
        tag.putInt("Color", color);
        if (owner != null) tag.putUUID("Owner", owner);
        if (price != null) tag.putLong("Price", price);
        var trust = new ListTag();
        trusted.forEach(u -> trust.add(net.minecraft.nbt.NbtUtils.createUUID(u)));
        tag.put("Trusted", trust);
        var rules = new CompoundTag();
        permissions.forEach(rules::putBoolean);
        tag.put("Rules", rules);
        var geometry = new ListTag();
        for (Box box : boxes) geometry.add(new IntArrayTag(new int[]{box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2()}));
        tag.put("Boxes", geometry);
        return tag;
    }

    static Parcel read(CompoundTag tag) {
        var parcel = new Parcel(tag.getUUID("Id"), tag.getString("Name"), Kind.valueOf(tag.getString("Kind")));
        if (tag.hasUUID("Owner")) parcel.owner = tag.getUUID("Owner");
        if (tag.contains("Price", Tag.TAG_LONG) && tag.getLong("Price") >= 0) parcel.price = tag.getLong("Price");
        parcel.group = tag.getString("Group");
        parcel.description = tag.getString("Description");
        parcel.color = tag.getInt("Color") & 0xffffff;
        tag.getList("Trusted", Tag.TAG_INT_ARRAY).forEach(t -> parcel.trusted.add(NbtUtils.loadUUID(t)));
        var rules = tag.getCompound("Rules");
        rules.getAllKeys().forEach(key -> parcel.permissions.put(key, rules.getBoolean(key)));
        for (Tag item : tag.getList("Boxes", Tag.TAG_INT_ARRAY)) {
            int[] a = ((IntArrayTag)item).getAsIntArray();
            if (a.length != 6) throw new IllegalArgumentException("Invalid cuboid");
            parcel.boxes.add(new Box(a[0], a[1], a[2], a[3], a[4], a[5]));
        }
        return parcel;
    }
}
