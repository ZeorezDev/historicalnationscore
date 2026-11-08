package dev.zeorez.historicalnations.core;

import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.api.Town;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.BiConsumer;

final class RegistryStorage {
    private static final Logger LOG = LogUtils.getLogger();

    static CompoundTag save(WorldRegistry registry, CompoundTag root) {
        ListTag towns = new ListTag();
        for (Town town : registry.towns.values()) {
            CompoundTag row = identity(town.id(), town.name());
            row.putUUID("Mayor", town.mayor());
            towns.add(row);
        }
        ListTag nations = new ListTag();
        for (Nation nation : registry.nations.values()) {
            CompoundTag row = identity(nation.id(), nation.name());
            row.putUUID("Capital", nation.capitalTown());
            nations.add(row);
        }
        root.put("Towns", towns);
        root.put("Nations", nations);
        root.put("Citizens", links(registry.playerTowns));
        root.put("Affiliations", links(registry.townNations));
        root.put("TownInvites", invites(registry.townInvites));
        root.put("NationInvites", invites(registry.nationInvites));
        return root;
    }

    private static CompoundTag identity(UUID id, String name) {
        CompoundTag row = new CompoundTag();
        row.putUUID("Id", id);
        row.putString("Name", name);
        return row;
    }
    private static CompoundTag link(UUID from, UUID to) {
        CompoundTag row = new CompoundTag();
        row.putUUID("From", from);
        row.putUUID("To", to);
        return row;
    }
    private static ListTag links(Map<UUID, UUID> memberships) {
        ListTag list = new ListTag();
        memberships.forEach((from, to) -> list.add(link(from, to)));
        return list;
    }
    private static ListTag invites(Set<WorldRegistry.Invitation> invitations) {
        ListTag list = new ListTag();
        invitations.forEach(i -> list.add(link(i.issuer(), i.recipient())));
        return list;
    }

    static WorldRegistry load(CompoundTag root) {
        WorldRegistry registry = new WorldRegistry();
        // Reserve mayors and capitals before accepting ordinary memberships.
        for (Tag entry : root.getList("Towns", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) entry;
            String name = readName(row);
            if (name == null || !row.hasUUID("Id") || !row.hasUUID("Mayor")) {
                LOG.warn("Skipping incomplete Historical Nations town");
                continue;
            }
            Town town = new Town(row.getUUID("Id"), name, row.getUUID("Mayor"));
            if (registry.towns.containsKey(town.id()) || registry.findTown(name).isPresent()
                    || registry.playerTowns.containsKey(town.mayor())) {
                LOG.warn("Skipping conflicting town {}", town.id());
                continue;
            }
            registry.towns.put(town.id(), town);
            registry.playerTowns.put(town.mayor(), town.id());
        }
        for (Tag entry : root.getList("Nations", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) entry;
            String name = readName(row);
            if (name == null || !row.hasUUID("Id") || !row.hasUUID("Capital")) {
                LOG.warn("Skipping incomplete Historical Nations nation");
                continue;
            }
            Nation nation = new Nation(row.getUUID("Id"), name, row.getUUID("Capital"));
            if (registry.nations.containsKey(nation.id()) || registry.findNation(name).isPresent()
                    || !registry.towns.containsKey(nation.capitalTown()) || registry.townNations.containsKey(nation.capitalTown())) {
                LOG.warn("Skipping conflicting or orphaned nation {}", nation.id());
                continue;
            }
            registry.nations.put(nation.id(), nation);
            registry.townNations.put(nation.capitalTown(), nation.id());
        }
        readLinks(root, "Citizens", (player, town) -> {
            if (registry.towns.containsKey(town)) registry.playerTowns.putIfAbsent(player, town);
        });
        readLinks(root, "Affiliations", (town, nation) -> {
            if (registry.towns.containsKey(town) && registry.nations.containsKey(nation))
                registry.townNations.putIfAbsent(town, nation);
        });
        readLinks(root, "TownInvites", (town, player) -> {
            if (registry.towns.containsKey(town) && !registry.playerTowns.containsKey(player))
                registry.townInvites.add(new WorldRegistry.Invitation(town, player));
        });
        readLinks(root, "NationInvites", (nation, town) -> {
            if (registry.nations.containsKey(nation) && registry.towns.containsKey(town) && !registry.townNations.containsKey(town))
                registry.nationInvites.add(new WorldRegistry.Invitation(nation, town));
        });
        return registry;
    }

    private static String readName(CompoundTag row) {
        try {
            return WorldRegistry.cleanName(row.getString("Name"));
        } catch (RuleViolation invalidName) {
            return null;
        }
    }
    private static void readLinks(CompoundTag root, String key, BiConsumer<UUID, UUID> accept) {
        for (Tag entry : root.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) entry;
            if (row.hasUUID("From") && row.hasUUID("To")) accept.accept(row.getUUID("From"), row.getUUID("To"));
        }
    }
}
