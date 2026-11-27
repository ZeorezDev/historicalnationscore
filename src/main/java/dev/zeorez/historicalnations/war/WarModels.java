package dev.zeorez.historicalnations.war;

import dev.zeorez.historicalnations.core.Territory.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent records, state machines, and network DTOs for Historical Nations Core v11.0.0 Flag War.
 * All identities use immutable UUIDs so town/nation renames never corrupt war state.
 */
public final class WarModels {
    private WarModels() {}

    public enum WarState {
        PREPARING,
        ACTIVE,
        PAUSED,
        CONCLUDED
    }

    public enum WarSide {
        ATTACKER,
        DEFENDER;

        public WarSide opposite() {
            return this == ATTACKER ? DEFENDER : ATTACKER;
        }
    }

    public enum FlagRunState {
        CAPTURING,
        PAUSED_NO_ATTACKER,
        PAUSED_WINDOW_CLOSED,
        PAUSED_SYSTEM
    }

    /** Canonical unordered pair of two Nation UUIDs (for alliances and truces). */
    public record NationPair(UUID first, UUID second) {
        public NationPair {
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
            if (first.compareTo(second) > 0) {
                UUID tmp = first;
                first = second;
                second = tmp;
            }
        }

        public boolean involves(UUID id) {
            return first.equals(id) || second.equals(id);
        }

        public UUID other(UUID id) {
            if (first.equals(id)) return second;
            if (second.equals(id)) return first;
            return null;
        }
    }

    public record TruceRecord(NationPair pair, long expiresAtEpochMs, UUID sourceWarId) {
        public boolean isActive(long nowMs) {
            return nowMs < expiresAtEpochMs;
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("First", pair.first());
            t.putUUID("Second", pair.second());
            t.putLong("ExpiresAt", expiresAtEpochMs);
            if (sourceWarId != null) t.putUUID("WarId", sourceWarId);
            return t;
        }

        public static TruceRecord load(CompoundTag t) {
            if (!t.hasUUID("First") || !t.hasUUID("Second")) return null;
            UUID a = t.getUUID("First");
            UUID b = t.getUUID("Second");
            if (a.equals(b)) return null;
            UUID warId = t.hasUUID("WarId") ? t.getUUID("WarId") : null;
            return new TruceRecord(new NationPair(a, b), t.getLong("ExpiresAt"), warId);
        }
    }

    /**
     * Temporary wartime occupation record. Preserves original Town & OPAC claim ownership
     * until a mutually ratified Peace Treaty either annexes or returns the chunk.
     */
    public record OccupiedChunk(
            Location location,
            UUID warId,
            UUID originalTownId,
            UUID originalNationId,
            UUID occupyingNationId,
            UUID capturedByPlayer,
            long capturedAtEpochMs,
            boolean homeChunk
    ) {
        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Dimension", location.dimension().toString());
            t.putInt("X", location.x());
            t.putInt("Z", location.z());
            t.putUUID("WarId", warId);
            t.putUUID("OriginalTown", originalTownId);
            if (originalNationId != null) t.putUUID("OriginalNation", originalNationId);
            t.putUUID("OccupyingNation", occupyingNationId);
            if (capturedByPlayer != null) t.putUUID("CapturedBy", capturedByPlayer);
            t.putLong("CapturedAt", capturedAtEpochMs);
            t.putBoolean("HomeChunk", homeChunk);
            return t;
        }

        public static OccupiedChunk load(CompoundTag t) {
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("Dimension"));
            if (dim == null || !t.hasUUID("WarId") || !t.hasUUID("OriginalTown") || !t.hasUUID("OccupyingNation")) {
                return null;
            }
            UUID origNation = t.hasUUID("OriginalNation") ? t.getUUID("OriginalNation") : null;
            UUID capturer = t.hasUUID("CapturedBy") ? t.getUUID("CapturedBy") : null;
            return new OccupiedChunk(
                    new Location(dim, t.getInt("X"), t.getInt("Z")),
                    t.getUUID("WarId"),
                    t.getUUID("OriginalTown"),
                    origNation,
                    t.getUUID("OccupyingNation"),
                    capturer,
                    t.getLong("CapturedAt"),
                    t.getBoolean("HomeChunk")
            );
        }
    }

    /** Deferred 3-block cleanup for flags cancelled while their chunk was unloaded. */
    public record PendingFlagCleanup(ResourceLocation dimension, BlockPos basePos) {
        public Location chunk() {
            return new Location(dimension, basePos.getX() >> 4, basePos.getZ() >> 4);
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Dimension", dimension.toString());
            t.putInt("BX", basePos.getX());
            t.putInt("BY", basePos.getY());
            t.putInt("BZ", basePos.getZ());
            return t;
        }

        public static PendingFlagCleanup load(CompoundTag t) {
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("Dimension"));
            if (dim == null) return null;
            return new PendingFlagCleanup(dim, new BlockPos(t.getInt("BX"), t.getInt("BY"), t.getInt("BZ")));
        }
    }

    /**
     * Mutable server-thread state of an active 3-block War Capture Flag (fence + progress wool + torch).
     * Progress advances strictly by verified server ticks (never wall-clock delta across restarts).
     */
    public static final class ActiveCaptureFlag {
        public final UUID flagId;
        public final UUID warId;
        public final Location chunk;
        public final BlockPos basePos;
        public final ResourceLocation fenceBlockId;
        public final UUID attackerPlayerId;
        public final UUID attackingNationId;
        public final UUID defendingTownId;
        public final UUID defendingNationId;
        public final int requiredTicks;

        public int elapsedTicks;
        public int unattendedTicks;
        public FlagRunState runState;
        public int lastWoolStage = -1;
        public transient ServerBossEvent bossBar;

        public ActiveCaptureFlag(
                UUID flagId,
                UUID warId,
                Location chunk,
                BlockPos basePos,
                ResourceLocation fenceBlockId,
                UUID attackerPlayerId,
                UUID attackingNationId,
                UUID defendingTownId,
                UUID defendingNationId,
                int requiredTicks,
                int elapsedTicks,
                int unattendedTicks,
                FlagRunState runState
        ) {
            this.flagId = flagId;
            this.warId = warId;
            this.chunk = chunk;
            this.basePos = basePos.immutable();
            this.fenceBlockId = fenceBlockId;
            this.attackerPlayerId = attackerPlayerId;
            this.attackingNationId = attackingNationId;
            this.defendingTownId = defendingTownId;
            this.defendingNationId = defendingNationId;
            this.requiredTicks = Math.max(20, requiredTicks);
            this.elapsedTicks = Math.max(0, elapsedTicks);
            this.unattendedTicks = Math.max(0, unattendedTicks);
            this.runState = runState == null ? FlagRunState.CAPTURING : runState;
        }

        public BlockPos woolPos() {
            return basePos.above();
        }

        public BlockPos torchPos() {
            return basePos.above(2);
        }

        public boolean ownsBlock(BlockPos pos) {
            return basePos.equals(pos) || woolPos().equals(pos) || torchPos().equals(pos);
        }

        public float progressFraction() {
            return Math.min(1.0F, (float) elapsedTicks / (float) requiredTicks);
        }

        public int progressPercent() {
            return Math.min(100, Math.round(progressFraction() * 100.0F));
        }

        public int remainingSeconds() {
            return Math.max(0, (requiredTicks - elapsedTicks + 19) / 20);
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("FlagId", flagId);
            t.putUUID("WarId", warId);
            t.putString("Dimension", chunk.dimension().toString());
            t.putInt("CX", chunk.x());
            t.putInt("CZ", chunk.z());
            t.putInt("BX", basePos.getX());
            t.putInt("BY", basePos.getY());
            t.putInt("BZ", basePos.getZ());
            t.putString("FenceId", fenceBlockId.toString());
            t.putUUID("Attacker", attackerPlayerId);
            t.putUUID("AttackingNation", attackingNationId);
            t.putUUID("DefendingTown", defendingTownId);
            if (defendingNationId != null) t.putUUID("DefendingNation", defendingNationId);
            t.putInt("RequiredTicks", requiredTicks);
            t.putInt("ElapsedTicks", elapsedTicks);
            t.putInt("UnattendedTicks", unattendedTicks);
            t.putString("RunState", runState.name());
            return t;
        }

        public static ActiveCaptureFlag load(CompoundTag t) {
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("Dimension"));
            ResourceLocation fence = ResourceLocation.tryParse(t.getString("FenceId"));
            if (dim == null || !t.hasUUID("FlagId") || !t.hasUUID("WarId")
                    || !t.hasUUID("Attacker") || !t.hasUUID("AttackingNation") || !t.hasUUID("DefendingTown")) {
                return null;
            }
            if (fence == null) fence = ResourceLocation.fromNamespaceAndPath("minecraft", "oak_fence");
            FlagRunState rs;
            try {
                rs = FlagRunState.valueOf(t.getString("RunState"));
            } catch (Exception ex) {
                rs = FlagRunState.CAPTURING;
            }
            UUID defNat = t.hasUUID("DefendingNation") ? t.getUUID("DefendingNation") : null;
            return new ActiveCaptureFlag(
                    t.getUUID("FlagId"),
                    t.getUUID("WarId"),
                    new Location(dim, t.getInt("CX"), t.getInt("CZ")),
                    new BlockPos(t.getInt("BX"), t.getInt("BY"), t.getInt("BZ")),
                    fence,
                    t.getUUID("Attacker"),
                    t.getUUID("AttackingNation"),
                    t.getUUID("DefendingTown"),
                    defNat,
                    t.getInt("RequiredTicks"),
                    t.getInt("ElapsedTicks"),
                    t.getInt("UnattendedTicks"),
                    rs
            );
        }
    }

    /**
     * Versioned bilateral Peace Treaty draft. Any edit increments version and invalidates the
     * opposing side's prior approval so neither side can be bait-and-switched.
     */
    public static final class PeaceTreatyDraft {
        public long version = 1L;
        /** Occupied chunks selected for permanent annexation -> recipient Town UUID. */
        public final Map<Location, UUID> annexations = new LinkedHashMap<>();
        public int truceHours = -1; // -1 = use server default truceHours
        public long reparations = 0L; // Extensible economic indemnity support
        public boolean attackerApproved = false;
        public boolean defenderApproved = false;
        public long updatedAtEpochMs = System.currentTimeMillis();

        public void recordMutation(WarSide editorSide) {
            version++;
            updatedAtEpochMs = System.currentTimeMillis();
            if (editorSide == WarSide.ATTACKER) {
                defenderApproved = false;
                attackerApproved = false;
            } else if (editorSide == WarSide.DEFENDER) {
                attackerApproved = false;
                defenderApproved = false;
            } else {
                attackerApproved = false;
                defenderApproved = false;
            }
        }

        public void resetApprovals() {
            attackerApproved = false;
            defenderApproved = false;
            version++;
            updatedAtEpochMs = System.currentTimeMillis();
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Version", version);
            t.putInt("TruceHours", truceHours);
            t.putLong("Reparations", reparations);
            t.putBoolean("AttackerApproved", attackerApproved);
            t.putBoolean("DefenderApproved", defenderApproved);
            t.putLong("UpdatedAt", updatedAtEpochMs);
            ListTag annexList = new ListTag();
            annexations.forEach((loc, townId) -> {
                CompoundTag row = new CompoundTag();
                row.putString("Dimension", loc.dimension().toString());
                row.putInt("X", loc.x());
                row.putInt("Z", loc.z());
                row.putUUID("RecipientTown", townId);
                annexList.add(row);
            });
            t.put("Annexations", annexList);
            return t;
        }

        public void load(CompoundTag t) {
            version = Math.max(1L, t.getLong("Version"));
            truceHours = t.contains("TruceHours", Tag.TAG_INT) ? t.getInt("TruceHours") : -1;
            reparations = Math.max(0L, t.getLong("Reparations"));
            attackerApproved = t.getBoolean("AttackerApproved");
            defenderApproved = t.getBoolean("DefenderApproved");
            updatedAtEpochMs = t.getLong("UpdatedAt");
            annexations.clear();
            for (Tag item : t.getList("Annexations", Tag.TAG_COMPOUND)) {
                CompoundTag row = (CompoundTag) item;
                ResourceLocation dim = ResourceLocation.tryParse(row.getString("Dimension"));
                if (dim != null && row.hasUUID("RecipientTown")) {
                    annexations.put(new Location(dim, row.getInt("X"), row.getInt("Z")), row.getUUID("RecipientTown"));
                }
            }
        }
    }

    public static final class WarStatistics {
        public int flagsPlaced;
        public int flagsDefended;
        public int attackerChunksOccupied;
        public int defenderChunksOccupied;
        public int townCentersCaptured;
        public int chunksAnnexed;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("FlagsPlaced", flagsPlaced);
            t.putInt("FlagsDefended", flagsDefended);
            t.putInt("AttackerOccupied", attackerChunksOccupied);
            t.putInt("DefenderOccupied", defenderChunksOccupied);
            t.putInt("TownCenters", townCentersCaptured);
            t.putInt("ChunksAnnexed", chunksAnnexed);
            return t;
        }

        public void load(CompoundTag t) {
            flagsPlaced = t.getInt("FlagsPlaced");
            flagsDefended = t.getInt("FlagsDefended");
            attackerChunksOccupied = t.getInt("AttackerOccupied");
            defenderChunksOccupied = t.getInt("DefenderOccupied");
            townCentersCaptured = t.getInt("TownCenters");
            chunksAnnexed = t.getInt("ChunksAnnexed");
        }
    }

    public static final class WarRecord {
        public final UUID warId;
        public final UUID primaryAttackerId;
        /** Nation ID, or Town ID when independentDefenderTown is true. */
        public final UUID primaryDefenderId;
        public final boolean independentDefenderTown;
        public final Set<UUID> attackerNations = new LinkedHashSet<>();
        public final Set<UUID> defenderNations = new LinkedHashSet<>();
        public final long declaredAtEpochMs;
        public long preparationEndsAtEpochMs;
        public WarState state;
        public long concludedAtEpochMs;
        public final Map<Location, OccupiedChunk> occupiedChunks = new LinkedHashMap<>();
        public final PeaceTreatyDraft peaceDraft = new PeaceTreatyDraft();
        public final WarStatistics stats = new WarStatistics();

        public WarRecord(
                UUID warId,
                UUID primaryAttackerId,
                UUID primaryDefenderId,
                boolean independentDefenderTown,
                long declaredAtEpochMs,
                long preparationEndsAtEpochMs,
                WarState state
        ) {
            this.warId = warId;
            this.primaryAttackerId = primaryAttackerId;
            this.primaryDefenderId = primaryDefenderId;
            this.independentDefenderTown = independentDefenderTown;
            this.declaredAtEpochMs = declaredAtEpochMs;
            this.preparationEndsAtEpochMs = preparationEndsAtEpochMs;
            this.state = state;
            this.attackerNations.add(primaryAttackerId);
            if (!independentDefenderTown) {
                this.defenderNations.add(primaryDefenderId);
            }
        }

        public boolean isOngoing() {
            return state != WarState.CONCLUDED;
        }

        public WarSide sideOfNation(UUID nationId) {
            if (nationId == null) return null;
            if (attackerNations.contains(nationId)) return WarSide.ATTACKER;
            if (!independentDefenderTown && defenderNations.contains(nationId)) return WarSide.DEFENDER;
            return null;
        }

        public boolean involvesNation(UUID nationId) {
            return sideOfNation(nationId) != null;
        }

        public boolean isPrimaryLeaderNation(UUID nationId) {
            if (primaryAttackerId.equals(nationId)) return true;
            return !independentDefenderTown && primaryDefenderId.equals(nationId);
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("WarId", warId);
            t.putUUID("PrimaryAttacker", primaryAttackerId);
            t.putUUID("PrimaryDefender", primaryDefenderId);
            t.putBoolean("IndependentDefender", independentDefenderTown);
            t.putLong("DeclaredAt", declaredAtEpochMs);
            t.putLong("PrepEndsAt", preparationEndsAtEpochMs);
            t.putString("State", state.name());
            t.putLong("ConcludedAt", concludedAtEpochMs);

            ListTag att = new ListTag();
            for (UUID id : attackerNations) {
                CompoundTag row = new CompoundTag();
                row.putUUID("Id", id);
                att.add(row);
            }
            t.put("Attackers", att);

            ListTag def = new ListTag();
            for (UUID id : defenderNations) {
                CompoundTag row = new CompoundTag();
                row.putUUID("Id", id);
                def.add(row);
            }
            t.put("Defenders", def);

            ListTag occ = new ListTag();
            for (OccupiedChunk oc : occupiedChunks.values()) {
                occ.add(oc.save());
            }
            t.put("Occupations", occ);

            t.put("PeaceDraft", peaceDraft.save());
            t.put("Stats", stats.save());
            return t;
        }

        public static WarRecord load(CompoundTag t) {
            if (!t.hasUUID("WarId") || !t.hasUUID("PrimaryAttacker") || !t.hasUUID("PrimaryDefender")) {
                return null;
            }
            WarState st;
            try {
                st = WarState.valueOf(t.getString("State"));
            } catch (Exception ex) {
                st = WarState.PAUSED;
            }
            WarRecord rec = new WarRecord(
                    t.getUUID("WarId"),
                    t.getUUID("PrimaryAttacker"),
                    t.getUUID("PrimaryDefender"),
                    t.getBoolean("IndependentDefender"),
                    t.getLong("DeclaredAt"),
                    t.getLong("PrepEndsAt"),
                    st
            );
            rec.concludedAtEpochMs = t.getLong("ConcludedAt");
            for (Tag row : t.getList("Attackers", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) row;
                if (c.hasUUID("Id")) rec.attackerNations.add(c.getUUID("Id"));
            }
            for (Tag row : t.getList("Defenders", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) row;
                if (c.hasUUID("Id")) rec.defenderNations.add(c.getUUID("Id"));
            }
            for (Tag row : t.getList("Occupations", Tag.TAG_COMPOUND)) {
                OccupiedChunk oc = OccupiedChunk.load((CompoundTag) row);
                if (oc != null) rec.occupiedChunks.put(oc.location(), oc);
            }
            if (t.contains("PeaceDraft", Tag.TAG_COMPOUND)) {
                rec.peaceDraft.load(t.getCompound("PeaceDraft"));
            }
            if (t.contains("Stats", Tag.TAG_COMPOUND)) {
                rec.stats.load(t.getCompound("Stats"));
            }
            return rec;
        }
    }

    // Compact network overlay DTOs for Political World Map (WAR MAP MODE & Occupation Layer)
    public record MapOccupationOverlay(
            int chunkX,
            int chunkZ,
            UUID warId,
            UUID originalNationId,
            UUID occupyingNationId,
            String warTitle
    ) {
        public void write(FriendlyByteBuf b) {
            b.writeInt(chunkX);
            b.writeInt(chunkZ);
            b.writeUUID(warId);
            b.writeNullable(originalNationId, FriendlyByteBuf::writeUUID);
            b.writeUUID(occupyingNationId);
            b.writeUtf(warTitle, 96);
        }

        public static MapOccupationOverlay read(FriendlyByteBuf b) {
            return new MapOccupationOverlay(
                    b.readInt(),
                    b.readInt(),
                    b.readUUID(),
                    b.readNullable(FriendlyByteBuf::readUUID),
                    b.readUUID(),
                    b.readUtf(96)
            );
        }
    }

    public record MapFlagOverlay(
            UUID flagId,
            UUID warId,
            int chunkX,
            int chunkZ,
            int blockX,
            int blockZ,
            UUID attackingNationId,
            UUID defendingNationId,
            UUID targetTownId,
            int progressPct,
            int remainingSeconds,
            FlagRunState runState
    ) {
        public void write(FriendlyByteBuf b) {
            b.writeUUID(flagId);
            b.writeUUID(warId);
            b.writeInt(chunkX);
            b.writeInt(chunkZ);
            b.writeInt(blockX);
            b.writeInt(blockZ);
            b.writeUUID(attackingNationId);
            b.writeNullable(defendingNationId, FriendlyByteBuf::writeUUID);
            b.writeUUID(targetTownId);
            b.writeVarInt(progressPct);
            b.writeVarInt(remainingSeconds);
            b.writeEnum(runState);
        }

        public static MapFlagOverlay read(FriendlyByteBuf b) {
            return new MapFlagOverlay(
                    b.readUUID(),
                    b.readUUID(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readUUID(),
                    b.readNullable(FriendlyByteBuf::readUUID),
                    b.readUUID(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readEnum(FlagRunState.class)
            );
        }
    }

    public static List<UUID> copyUuidList(Set<UUID> set) {
        return new ArrayList<>(set);
    }
}
