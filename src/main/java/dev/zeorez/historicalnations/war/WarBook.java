package dev.zeorez.historicalnations.war;

import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.war.WarModels.ActiveCaptureFlag;
import dev.zeorez.historicalnations.war.WarModels.NationPair;
import dev.zeorez.historicalnations.war.WarModels.OccupiedChunk;
import dev.zeorez.historicalnations.war.WarModels.PendingFlagCleanup;
import dev.zeorez.historicalnations.war.WarModels.TruceRecord;
import dev.zeorez.historicalnations.war.WarModels.WarRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent war, alliance, truce, occupation, and capture-flag store owned by WorldRegistry.
 * Maintains O(1) spatial indexes for active flags and occupied chunks so server tick and
 * protection hooks never scan all world claims.
 */
public final class WarBook {
    public record BlockKey(ResourceLocation dimension, BlockPos pos) {}
    public record AllianceInvite(UUID fromNation, UUID toNation) {}

    private final WorldRegistry registry;
    private boolean runtimeEnabledOverride = true;

    private final Set<NationPair> alliances = new LinkedHashSet<>();
    private final Set<AllianceInvite> allianceInvites = new LinkedHashSet<>();
    private final Map<NationPair, TruceRecord> truces = new LinkedHashMap<>();
    private final Map<UUID, WarRecord> wars = new LinkedHashMap<>();
    private final Map<Location, ActiveCaptureFlag> activeFlagsByChunk = new LinkedHashMap<>();
    private final Map<BlockKey, ActiveCaptureFlag> activeFlagsByBlock = new HashMap<>();
    private final Map<Location, OccupiedChunk> globalOccupations = new HashMap<>();
    private final Set<PendingFlagCleanup> pendingCleanups = new LinkedHashSet<>();

    public WarBook(WorldRegistry registry) {
        this.registry = registry;
    }

    public boolean isRuntimeEnabled() {
        return runtimeEnabledOverride;
    }

    public void setRuntimeEnabled(boolean enabled) {
        this.runtimeEnabledOverride = enabled;
        registry.setDirty();
    }

    // --- Alliances ---

    public boolean areAllied(UUID nationA, UUID nationB) {
        if (nationA == null || nationB == null || nationA.equals(nationB)) return false;
        return alliances.contains(new NationPair(nationA, nationB));
    }

    public List<UUID> alliesOf(UUID nationId) {
        if (nationId == null) return List.of();
        List<UUID> out = new ArrayList<>();
        for (NationPair pair : alliances) {
            UUID other = pair.other(nationId);
            if (other != null && registry.getNationById(other).isPresent()) {
                out.add(other);
            }
        }
        return out;
    }

    public boolean hasAllianceInvite(UUID fromNation, UUID toNation) {
        return allianceInvites.contains(new AllianceInvite(fromNation, toNation));
    }

    public List<UUID> incomingAllianceInvites(UUID toNation) {
        List<UUID> out = new ArrayList<>();
        for (AllianceInvite inv : allianceInvites) {
            if (inv.toNation().equals(toNation)) out.add(inv.fromNation());
        }
        return out;
    }

    /** Returns true if a mutual alliance was formed immediately, false if an invitation was sent. */
    public boolean proposeOrAcceptAlliance(UUID actorNation, UUID targetNation) {
        NationPair pair = new NationPair(actorNation, targetNation);
        if (alliances.contains(pair)) {
            return true;
        }
        if (allianceInvites.remove(new AllianceInvite(targetNation, actorNation))) {
            allianceInvites.remove(new AllianceInvite(actorNation, targetNation));
            alliances.add(pair);
            registry.setDirty();
            return true;
        }
        allianceInvites.add(new AllianceInvite(actorNation, targetNation));
        registry.setDirty();
        return false;
    }

    public boolean removeAlliance(UUID nationA, UUID nationB) {
        boolean removed = alliances.remove(new NationPair(nationA, nationB));
        removed |= allianceInvites.remove(new AllianceInvite(nationA, nationB));
        removed |= allianceInvites.remove(new AllianceInvite(nationB, nationA));
        if (removed) registry.setDirty();
        return removed;
    }

    // --- Truces ---

    public Optional<TruceRecord> activeTruce(UUID nationA, UUID nationB, long nowMs) {
        if (nationA == null || nationB == null || nationA.equals(nationB)) return Optional.empty();
        NationPair pair = new NationPair(nationA, nationB);
        TruceRecord rec = truces.get(pair);
        if (rec == null) return Optional.empty();
        if (!rec.isActive(nowMs)) {
            truces.remove(pair);
            registry.setDirty();
            return Optional.empty();
        }
        return Optional.of(rec);
    }

    public List<TruceRecord> activeTrucesFor(UUID nationId, long nowMs) {
        if (nationId == null) return List.of();
        List<TruceRecord> out = new ArrayList<>();
        var it = truces.entrySet().iterator();
        boolean pruned = false;
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getValue().isActive(nowMs)) {
                it.remove();
                pruned = true;
                continue;
            }
            if (e.getKey().involves(nationId)) {
                out.add(e.getValue());
            }
        }
        if (pruned) registry.setDirty();
        return out;
    }

    public void putTruce(UUID nationA, UUID nationB, long expiresAtEpochMs, UUID sourceWarId) {
        if (nationA == null || nationB == null || nationA.equals(nationB)) return;
        if (expiresAtEpochMs <= System.currentTimeMillis()) return;
        NationPair pair = new NationPair(nationA, nationB);
        TruceRecord existing = truces.get(pair);
        if (existing == null || expiresAtEpochMs > existing.expiresAtEpochMs()) {
            truces.put(pair, new TruceRecord(pair, expiresAtEpochMs, sourceWarId));
            registry.setDirty();
        }
    }

    // --- Wars & Spatial Indexes ---

    public Collection<WarRecord> allWars() {
        return Collections.unmodifiableCollection(wars.values());
    }

    public List<WarRecord> ongoingWars() {
        return wars.values().stream().filter(WarRecord::isOngoing).toList();
    }

    public List<WarRecord> ongoingWarsOfNation(UUID nationId) {
        if (nationId == null) return List.of();
        return wars.values().stream()
                .filter(w -> w.isOngoing() && w.involvesNation(nationId))
                .toList();
    }

    public boolean isNationInOngoingWar(UUID nationId) {
        if (nationId == null) return false;
        for (WarRecord w : wars.values()) {
            if (w.isOngoing() && w.involvesNation(nationId)) return true;
        }
        return false;
    }

    public boolean isTownInOngoingWar(UUID townId) {
        if (townId == null) return false;
        UUID nationId = registry.getTownNation(townId).map(dev.zeorez.historicalnations.api.Nation::id).orElse(null);
        for (WarRecord w : wars.values()) {
            if (!w.isOngoing()) continue;
            if (w.independentDefenderTown && w.primaryDefenderId.equals(townId)) return true;
            if (nationId != null && w.involvesNation(nationId)) return true;
        }
        return false;
    }

    public Optional<WarRecord> warById(UUID warId) {
        return warId == null ? Optional.empty() : Optional.ofNullable(wars.get(warId));
    }

    public Optional<WarRecord> findOngoingWarBetween(UUID nationA, UUID defenderId, boolean independentDefender) {
        for (WarRecord w : wars.values()) {
            if (!w.isOngoing()) continue;
            if (independentDefender) {
                if (w.independentDefenderTown && w.primaryDefenderId.equals(defenderId) && w.attackerNations.contains(nationA)) {
                    return Optional.of(w);
                }
            } else if (!w.independentDefenderTown) {
                boolean ab = w.attackerNations.contains(nationA) && w.defenderNations.contains(defenderId);
                boolean ba = w.attackerNations.contains(defenderId) && w.defenderNations.contains(nationA);
                if (ab || ba) return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    public void addWar(WarRecord war) {
        wars.put(war.warId, war);
        war.occupiedChunks.values().forEach(oc -> globalOccupations.put(oc.location(), oc));
        registry.setDirty();
    }

    public OccupiedChunk occupationAt(Location location) {
        return globalOccupations.get(location);
    }

    public Map<Location, OccupiedChunk> allOccupations() {
        return Collections.unmodifiableMap(globalOccupations);
    }

    public void putOccupation(WarRecord war, OccupiedChunk occupation) {
        war.occupiedChunks.put(occupation.location(), occupation);
        globalOccupations.put(occupation.location(), occupation);
        war.peaceDraft.resetApprovals();
        registry.setDirty();
    }

    public void removeOccupation(WarRecord war, Location location) {
        war.occupiedChunks.remove(location);
        OccupiedChunk cur = globalOccupations.get(location);
        if (cur != null && cur.warId().equals(war.warId)) {
            globalOccupations.remove(location);
        }
        registry.setDirty();
    }

    public void clearWarOccupations(WarRecord war) {
        for (Location loc : new ArrayList<>(war.occupiedChunks.keySet())) {
            OccupiedChunk cur = globalOccupations.get(loc);
            if (cur != null && cur.warId().equals(war.warId)) {
                globalOccupations.remove(loc);
            }
        }
        war.occupiedChunks.clear();
        registry.setDirty();
    }

    // --- Active Capture Flags ---

    public Collection<ActiveCaptureFlag> activeFlags() {
        return Collections.unmodifiableCollection(activeFlagsByChunk.values());
    }

    public ActiveCaptureFlag flagAtChunk(Location chunk) {
        return activeFlagsByChunk.get(chunk);
    }

    public ActiveCaptureFlag flagAtBlock(ResourceLocation dimension, BlockPos pos) {
        return activeFlagsByBlock.get(new BlockKey(dimension, pos));
    }

    public int countActiveFlagsByPlayer(UUID playerId) {
        int count = 0;
        for (ActiveCaptureFlag f : activeFlagsByChunk.values()) {
            if (f.attackerPlayerId.equals(playerId)) count++;
        }
        return count;
    }

    public int countActiveFlagsByNation(UUID nationId) {
        int count = 0;
        for (ActiveCaptureFlag f : activeFlagsByChunk.values()) {
            if (f.attackingNationId.equals(nationId)) count++;
        }
        return count;
    }

    public List<ActiveCaptureFlag> activeFlagsOfWar(UUID warId) {
        return activeFlagsByChunk.values().stream().filter(f -> f.warId.equals(warId)).toList();
    }

    public void registerFlag(ActiveCaptureFlag flag) {
        activeFlagsByChunk.put(flag.chunk, flag);
        indexFlagBlocks(flag);
        registry.setDirty();
    }

    public ActiveCaptureFlag unregisterFlag(Location chunk) {
        ActiveCaptureFlag removed = activeFlagsByChunk.remove(chunk);
        if (removed != null) {
            unindexFlagBlocks(removed);
            if (removed.bossBar != null) {
                removed.bossBar.removeAllPlayers();
                removed.bossBar = null;
            }
            registry.setDirty();
        }
        return removed;
    }

    public void addPendingCleanup(PendingFlagCleanup cleanup) {
        if (pendingCleanups.add(cleanup)) {
            registry.setDirty();
        }
    }

    public List<PendingFlagCleanup> consumePendingCleanupsForChunk(Location chunk) {
        if (pendingCleanups.isEmpty()) return List.of();
        List<PendingFlagCleanup> matched = new ArrayList<>();
        var it = pendingCleanups.iterator();
        while (it.hasNext()) {
            PendingFlagCleanup item = it.next();
            if (item.chunk().equals(chunk)) {
                matched.add(item);
                it.remove();
            }
        }
        if (!matched.isEmpty()) registry.setDirty();
        return matched;
    }

    public void onTownDisbanded(UUID townId) {
        for (WarRecord war : wars.values()) {
            war.occupiedChunks.entrySet().removeIf(e -> {
                if (e.getValue().originalTownId().equals(townId)) {
                    globalOccupations.remove(e.getKey());
                    return true;
                }
                return false;
            });
            war.peaceDraft.annexations.values().removeIf(townId::equals);
        }
        registry.setDirty();
    }

    public void onNationDisbanded(UUID nationId) {
        alliances.removeIf(p -> p.involves(nationId));
        allianceInvites.removeIf(i -> i.fromNation().equals(nationId) || i.toNation().equals(nationId));
        truces.keySet().removeIf(p -> p.involves(nationId));
        registry.setDirty();
    }

    private void indexFlagBlocks(ActiveCaptureFlag f) {
        ResourceLocation dim = f.chunk.dimension();
        activeFlagsByBlock.put(new BlockKey(dim, f.basePos), f);
        activeFlagsByBlock.put(new BlockKey(dim, f.woolPos()), f);
        activeFlagsByBlock.put(new BlockKey(dim, f.torchPos()), f);
    }

    private void unindexFlagBlocks(ActiveCaptureFlag f) {
        ResourceLocation dim = f.chunk.dimension();
        activeFlagsByBlock.remove(new BlockKey(dim, f.basePos));
        activeFlagsByBlock.remove(new BlockKey(dim, f.woolPos()));
        activeFlagsByBlock.remove(new BlockKey(dim, f.torchPos()));
    }

    // --- NBT Persistence (embedded inside WorldRegistry SavedData) ---

    public void save(CompoundTag root) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("RuntimeEnabled", runtimeEnabledOverride);

        ListTag allyList = new ListTag();
        for (NationPair p : alliances) {
            CompoundTag c = new CompoundTag();
            c.putUUID("First", p.first());
            c.putUUID("Second", p.second());
            allyList.add(c);
        }
        tag.put("Alliances", allyList);

        ListTag invList = new ListTag();
        for (AllianceInvite inv : allianceInvites) {
            CompoundTag c = new CompoundTag();
            c.putUUID("From", inv.fromNation());
            c.putUUID("To", inv.toNation());
            invList.add(c);
        }
        tag.put("AllianceInvites", invList);

        ListTag truceList = new ListTag();
        for (TruceRecord tr : truces.values()) {
            truceList.add(tr.save());
        }
        tag.put("Truces", truceList);

        ListTag warList = new ListTag();
        for (WarRecord w : wars.values()) {
            warList.add(w.save());
        }
        tag.put("Wars", warList);

        ListTag flagList = new ListTag();
        for (ActiveCaptureFlag f : activeFlagsByChunk.values()) {
            flagList.add(f.save());
        }
        tag.put("ActiveFlags", flagList);

        ListTag cleanList = new ListTag();
        for (PendingFlagCleanup pfc : pendingCleanups) {
            cleanList.add(pfc.save());
        }
        tag.put("PendingCleanups", cleanList);

        root.put("WarData", tag);
    }

    public void load(CompoundTag root) {
        alliances.clear();
        allianceInvites.clear();
        truces.clear();
        wars.clear();
        activeFlagsByChunk.clear();
        activeFlagsByBlock.clear();
        globalOccupations.clear();
        pendingCleanups.clear();

        if (!root.contains("WarData", Tag.TAG_COMPOUND)) {
            return; // Seamless backward compatibility with v0.1..v10.0 saves
        }

        CompoundTag tag = root.getCompound("WarData");
        runtimeEnabledOverride = !tag.contains("RuntimeEnabled", Tag.TAG_BYTE) || tag.getBoolean("RuntimeEnabled");

        for (Tag item : tag.getList("Alliances", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) item;
            if (c.hasUUID("First") && c.hasUUID("Second")) {
                UUID a = c.getUUID("First");
                UUID b = c.getUUID("Second");
                if (!a.equals(b) && registry.getNationById(a).isPresent() && registry.getNationById(b).isPresent()) {
                    alliances.add(new NationPair(a, b));
                }
            }
        }
        for (Tag item : tag.getList("AllianceInvites", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) item;
            if (c.hasUUID("From") && c.hasUUID("To")) {
                UUID a = c.getUUID("From");
                UUID b = c.getUUID("To");
                if (!a.equals(b) && registry.getNationById(a).isPresent() && registry.getNationById(b).isPresent()) {
                    allianceInvites.add(new AllianceInvite(a, b));
                }
            }
        }
        for (Tag item : tag.getList("Truces", Tag.TAG_COMPOUND)) {
            TruceRecord tr = TruceRecord.load((CompoundTag) item);
            if (tr != null) {
                truces.put(tr.pair(), tr);
            }
        }

        Set<Location> occupiedSeen = new HashSet<>();
        for (Tag item : tag.getList("Wars", Tag.TAG_COMPOUND)) {
            WarRecord w = WarRecord.load((CompoundTag) item);
            if (w == null) continue;
            // Reconcile dangling occupations if a chunk was deleted while offline
            w.occupiedChunks.entrySet().removeIf(e -> {
                if (!w.isOngoing()) return true;
                if (registry.territory(e.getKey()) == null || !occupiedSeen.add(e.getKey())) {
                    return true;
                }
                return false;
            });
            wars.put(w.warId, w);
            if (w.isOngoing()) {
                w.occupiedChunks.values().forEach(oc -> globalOccupations.put(oc.location(), oc));
            }
        }

        for (Tag item : tag.getList("ActiveFlags", Tag.TAG_COMPOUND)) {
            ActiveCaptureFlag f = ActiveCaptureFlag.load((CompoundTag) item);
            if (f == null) continue;
            WarRecord w = wars.get(f.warId);
            if (w == null || !w.isOngoing() || activeFlagsByChunk.containsKey(f.chunk)
                    || registry.territory(f.chunk) == null) {
                // Queue cleanup of stale flag blocks on disk so world stays clean
                pendingCleanups.add(new PendingFlagCleanup(f.chunk.dimension(), f.basePos));
                continue;
            }
            activeFlagsByChunk.put(f.chunk, f);
            indexFlagBlocks(f);
        }

        for (Tag item : tag.getList("PendingCleanups", Tag.TAG_COMPOUND)) {
            PendingFlagCleanup pfc = PendingFlagCleanup.load((CompoundTag) item);
            if (pfc != null) pendingCleanups.add(pfc);
        }
    }
}
