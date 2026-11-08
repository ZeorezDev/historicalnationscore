package dev.zeorez.historicalnations.core;

import dev.zeorez.historicalnations.api.HistoricalNationsApi;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.api.Nation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** One registry per save, accessed exclusively on its logical server thread. */
public final class WorldRegistry extends SavedData implements HistoricalNationsApi {
    static final String FILE = "historical_nations";
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} _-]{0,31}");
    final Map<UUID, Town> towns = new LinkedHashMap<>();
    final Map<UUID, Nation> nations = new LinkedHashMap<>();
    final Map<UUID, UUID> playerTowns = new HashMap<>();
    final Map<UUID, UUID> townNations = new HashMap<>();
    final Set<Invitation> townInvites = new LinkedHashSet<>();
    final Set<Invitation> nationInvites = new LinkedHashSet<>();
    final Map<Territory.Location, Territory> territories = new LinkedHashMap<>();

    public Territory territory(Territory.Location location) {
        return territories.get(location);
    }

    public List<Territory.Location> territoryLocations(UUID town) {
        return territories.entrySet().stream().filter(e -> e.getValue().town().equals(town))
                .map(Map.Entry::getKey).toList();
    }

    public Set<Territory.Location> territoryLocations() {
        return Set.copyOf(territories.keySet());
    }

    public void link(Territory.Location location, Territory territory) {
        require(territories.putIfAbsent(location, territory) == null, "land_occupied");
        setDirty();
    }

    public void unlink(Territory.Location location) {
        if (territories.remove(location) != null) setDirty();
    }

    record Invitation(UUID issuer, UUID recipient) {}

    public static WorldRegistry get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Access requires the server thread");
        return server.overworld().getDataStorage().computeIfAbsent(RegistryStorage::load, WorldRegistry::new, FILE);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        return RegistryStorage.save(this, tag);
    }

    static String cleanName(String name) {
        String clean = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        require(NAME.matcher(clean).matches(), "invalid_name");
        return clean;
    }

    public Optional<Town> getTownById(UUID id) { return Optional.ofNullable(towns.get(id)); }
    public Optional<Nation> getNationById(UUID id) { return Optional.ofNullable(nations.get(id)); }
    public Optional<Town> getPlayerTown(UUID player) { return getTownById(playerTowns.get(player)); }
    public Optional<Nation> getTownNation(UUID town) { return getNationById(townNations.get(town)); }
    public Optional<Nation> getPlayerNation(UUID player) { return getTownNation(playerTowns.get(player)); }
    public boolean isTownMayor(UUID player) {
        return getPlayerTown(player).map(t -> t.mayor().equals(player)).orElse(false);
    }
    public boolean isNationLeader(UUID player) {
        return getPlayerNation(player).flatMap(n -> getTownById(n.capitalTown()))
                .map(capital -> capital.mayor().equals(player)).orElse(false);
    }
    public boolean isPlayerCitizenOfNation(UUID player, UUID nation) {
        return getPlayerNation(player).map(n -> n.id().equals(nation)).orElse(false);
    }
    public List<Town> getNationTowns(UUID nation) {
        return towns.values().stream().filter(t -> nation != null && nation.equals(townNations.get(t.id()))).toList();
    }
    public List<UUID> members(UUID town) {
        return playerTowns.entrySet().stream().filter(e -> e.getValue().equals(town)).map(Map.Entry::getKey).sorted().toList();
    }
    public List<Town> towns() { return List.copyOf(towns.values()); }
    public List<Nation> nations() { return List.copyOf(nations.values()); }
    Optional<Town> findTown(String name) {
        return towns.values().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
    }
    Optional<Nation> findNation(String name) {
        return nations.values().stream().filter(n -> n.name().equalsIgnoreCase(name)).findFirst();
    }
    public Town namedTown(String name) {
        return findTown(cleanName(name)).orElseThrow(() -> new RuleViolation("unknown_town"));
    }
    public Nation namedNation(String name) {
        return findNation(cleanName(name)).orElseThrow(() -> new RuleViolation("unknown_nation"));
    }
    public Town home(UUID player) {
        return getPlayerTown(player).orElseThrow(() -> new RuleViolation("no_town"));
    }
    public Nation country(UUID player) {
        return getPlayerNation(player).orElseThrow(() -> new RuleViolation("no_nation"));
    }
    private Town mayorTown(UUID player) {
        Town town = home(player);
        require(town.mayor().equals(player), "mayor_only");
        return town;
    }
    private Nation ledNation(UUID player) {
        Nation nation = country(player);
        require(isNationLeader(player), "leader_only");
        return nation;
    }

    public Town createTown(UUID actor, String name) {
        require(!playerTowns.containsKey(actor), "already_citizen");
        String clean = cleanName(name);
        require(findTown(clean).isEmpty(), "name_taken");
        Town town = new Town(UUID.randomUUID(), clean, actor);
        towns.put(town.id(), town);
        joinTown(actor, town);
        return town;
    }
    private void joinTown(UUID player, Town town) {
        playerTowns.put(player, town.id());
        townInvites.removeIf(i -> i.recipient().equals(player));
        setDirty();
    }
    public Town invitePlayer(UUID actor, UUID target) {
        Town town = mayorTown(actor);
        require(!playerTowns.containsKey(target), "already_citizen");
        require(townInvites.add(new Invitation(town.id(), target)), "already_invited");
        setDirty();
        return town;
    }
    public void answerTown(UUID actor, Town town, boolean accept) {
        require(!playerTowns.containsKey(actor), "already_citizen");
        require(townInvites.remove(new Invitation(town.id(), actor)), "no_invitation");
        if (accept) joinTown(actor, town);
        setDirty();
    }
    public void transferMayor(UUID actor, UUID successor) {
        Town town = mayorTown(actor);
        require(!actor.equals(successor) && town.id().equals(playerTowns.get(successor)), "invalid_successor");
        require(territoryLocations(town.id()).isEmpty(), "land_transfer");
        towns.put(town.id(), new Town(town.id(), town.name(), successor));
        setDirty();
    }
    public void leaveTown(UUID actor) {
        Town town = home(actor);
        require(!town.mayor().equals(actor), "transfer_first");
        playerTowns.remove(actor);
        setDirty();
    }
    public Town checkDisband(UUID actor) {
        Town town = mayorTown(actor);
        requireNonCapital(town);
        return town;
    }
    public void disbandTown(UUID actor) {
        Town town = checkDisband(actor);
        require(territoryLocations(town.id()).isEmpty(), "land_remaining");
        towns.remove(town.id());
        playerTowns.values().removeIf(town.id()::equals);
        townNations.remove(town.id());
        townInvites.removeIf(i -> i.issuer().equals(town.id()));
        nationInvites.removeIf(i -> i.recipient().equals(town.id()));
        setDirty();
    }

    public Nation createNation(UUID actor, String name) {
        Town town = mayorTown(actor);
        require(!townNations.containsKey(town.id()), "already_in_nation");
        String clean = cleanName(name);
        require(findNation(clean).isEmpty(), "name_taken");
        Nation nation = new Nation(UUID.randomUUID(), clean, town.id());
        nations.put(nation.id(), nation);
        joinNation(town, nation);
        return nation;
    }
    private void joinNation(Town town, Nation nation) {
        townNations.put(town.id(), nation.id());
        nationInvites.removeIf(i -> i.recipient().equals(town.id()));
        setDirty();
    }
    public Nation inviteTown(UUID actor, Town target) {
        Nation nation = ledNation(actor);
        require(!townNations.containsKey(target.id()), "already_in_nation");
        require(nationInvites.add(new Invitation(nation.id(), target.id())), "already_invited");
        setDirty();
        return nation;
    }
    public void answerNation(UUID actor, Nation nation, boolean accept) {
        Town town = mayorTown(actor);
        require(!townNations.containsKey(town.id()), "already_in_nation");
        require(nationInvites.remove(new Invitation(nation.id(), town.id())), "no_invitation");
        if (accept) joinNation(town, nation);
        setDirty();
    }
    private void requireNonCapital(Town town) {
        require(getTownNation(town.id()).map(n -> !n.capitalTown().equals(town.id())).orElse(true), "capital");
    }
    public void leaveNation(UUID actor) {
        Town town = mayorTown(actor);
        country(actor);
        requireNonCapital(town);
        townNations.remove(town.id());
        setDirty();
    }
    public void disbandNation(UUID actor) {
        Nation nation = ledNation(actor);
        nations.remove(nation.id());
        townNations.values().removeIf(nation.id()::equals);
        nationInvites.removeIf(i -> i.issuer().equals(nation.id()));
        setDirty();
    }
    static void require(boolean condition, String key) {
        if (!condition) throw new RuleViolation(key);
    }
}
