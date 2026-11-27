package dev.zeorez.historicalnations.core;

import dev.zeorez.historicalnations.api.HistoricalNationsApi;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.map.NationPalette;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** One registry per save, accessed exclusively on its logical server thread. */
public final class WorldRegistry extends SavedData implements HistoricalNationsApi {
    public final dev.zeorez.historicalnations.property.PropertyBook properties =
            new dev.zeorez.historicalnations.property.PropertyBook(this);
    public final dev.zeorez.historicalnations.war.WarBook wars =
            new dev.zeorez.historicalnations.war.WarBook(this);
    PoliticalPanel.Counts directoryCounts;
    final Map<UUID, List<CityEvaluation>> evaluations = new HashMap<>();

    public CityEvaluation evaluation(UUID town) {
        var history = evaluations.get(town);
        return history == null || history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public void evaluate(net.minecraft.server.level.ServerPlayer player, UUID town, int revision, CityEvaluation.Draft draft) {
        if (!player.hasPermissions(4)) throw new RuleViolation("city_permission");
        if (!towns.containsKey(town)) throw new RuleViolation("unknown_town");
        draft.validate();
        var history = evaluations.computeIfAbsent(town, key -> new ArrayList<>());
        if (revision != history.size()) throw new RuleViolation("city_conflict");
        history.add(new CityEvaluation(player.getUUID(), System.currentTimeMillis(), draft));
        super.setDirty();
        dev.zeorez.historicalnations.map.PoliticalMapServer.get(player.server).evaluationChanged(town, draft);
        dev.zeorez.historicalnations.gui.PanelNetwork.refreshTown(player.server, town);
    }
    private boolean mapDirty = true;
    UUID mapId = UUID.randomUUID();
    @Override
    public void setDirty() {
        directoryCounts = null;
        mapDirty = true;
        super.setDirty();
    }
    public UUID mapId() { return mapId; }
    public boolean consumeMapDirty() {
        if (!mapDirty) return false;
        mapDirty = false;
        return true;
    }
    final Ledger ledger = new Ledger(this);
    final TradeBook trade = new TradeBook(this);
    static final String FILE = "historical_nations";
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} _-]{0,31}");
    final Map<UUID, Town> towns = new LinkedHashMap<>();
    final Map<UUID, Nation> nations = new LinkedHashMap<>();
    final Map<UUID, Integer> nationColors = new HashMap<>();
    final Map<UUID, SpawnPoint> townSpawns = new HashMap<>();
    final Map<UUID, SpawnPoint> nationSpawns = new HashMap<>();
    final Map<UUID, UUID> deputies = new HashMap<>();
    final Map<UUID, String> descriptions = new HashMap<>();
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
        properties.add(location);
        setDirty();
    }

    public void unlink(Territory.Location location) {
        if (territories.remove(location) != null) {
            properties.remove(location);
            setDirty();
        }
    }

    /** Atomically replaces HNC territory ownership during verified wartime annexation while keeping plots/quarters intact. */
    public void replaceTerritoryForAnnexation(Territory.Location location, Territory newTerritory) {
        Territory old = territories.put(location, newTerritory);
        if (old != null && !old.town().equals(newTerritory.town())) {
            SpawnPoint tsp = townSpawns.get(old.town());
            if (tsp != null && tsp.chunkLocation().equals(location)) {
                townSpawns.remove(old.town());
            }
            getTownNation(old.town()).ifPresent(oldNat -> {
                SpawnPoint nsp = nationSpawns.get(oldNat.id());
                if (nsp != null && nsp.chunkLocation().equals(location)) {
                    nationSpawns.remove(oldNat.id());
                }
            });
        }
        setDirty();
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
    Nation ledNation(UUID player) {
        Nation nation = country(player);
        require(isNationLeader(player), "leader_only");
        return nation;
    }

    public String officialTownCurrency(UUID town) { return ledger.market.official(townNations.get(town)); }

    public String checkCreateTown(UUID actor, String name) {
        require(!playerTowns.containsKey(actor), "already_citizen");
        String clean = cleanName(name);
        require(findTown(clean).isEmpty(), "name_taken");
        return clean;
    }

    public Town createTown(UUID actor, String name) {
        String clean = checkCreateTown(actor, name);
        Town town = new Town(UUID.randomUUID(), clean, actor);
        towns.put(town.id(), town);
        joinTown(actor, town);
        return town;
    }

    public Town createTownWithTerritory(UUID townId, UUID actor, String name, Territory.Location location, Territory territory) {
        String clean = checkCreateTown(actor, name);
        require(!towns.containsKey(townId), "name_taken");
        require(!territories.containsKey(location), "land_occupied");
        Town town = new Town(townId, clean, actor);
        towns.put(town.id(), town);
        joinTown(actor, town);
        territories.put(location, territory);
        properties.add(location);
        setDirty();
        return town;
    }
    private void joinTown(UUID player, Town town) {
        playerTowns.put(player, town.id());
        townInvites.removeIf(i -> i.recipient().equals(player));
        setDirty();
    }
    public UUID deputy(UUID town) { return deputies.get(town); }
    public String description(UUID town) { return descriptions.getOrDefault(town, ""); }
    public SpawnPoint townSpawn(UUID town) { return town == null ? null : townSpawns.get(town); }
    public SpawnPoint nationSpawn(UUID nation) { return nation == null ? null : nationSpawns.get(nation); }

    public void putTownSpawn(UUID townId, SpawnPoint spawn) {
        require(towns.containsKey(townId), "unknown_town");
        if (spawn == null) townSpawns.remove(townId);
        else townSpawns.put(townId, spawn);
        setDirty();
    }

    public void putNationSpawn(UUID nationId, SpawnPoint spawn) {
        require(nations.containsKey(nationId), "unknown_nation");
        if (spawn == null) nationSpawns.remove(nationId);
        else nationSpawns.put(nationId, spawn);
        setDirty();
    }

    private Town administration(UUID actor) {
        Town town = home(actor);
        require(town.mayor().equals(actor) || actor.equals(deputy(town.id())), "mayor_only");
        return town;
    }

    public void appoint(UUID actor, UUID target) {
        Town town = mayorTown(actor);
        if (target == null) deputies.remove(town.id());
        else {
            require(!target.equals(actor) && town.id().equals(playerTowns.get(target)), "invalid_successor");
            deputies.put(town.id(), target);
        }
        setDirty();
    }

    public void describe(UUID actor, String text) {
        Town town = administration(actor);
        require(text.length() <= 256 && text.chars().noneMatch(Character::isISOControl), "invalid_text");
        if (text.isBlank()) descriptions.remove(town.id());
        else descriptions.put(town.id(), text.strip());
        setDirty();
    }

    public void kick(UUID actor, UUID target) {
        Town town = administration(actor);
        require(!actor.equals(target) && town.id().equals(playerTowns.get(target))
                && !target.equals(town.mayor()), "invalid_successor");
        leaveTown(target);
    }

    public Town invitePlayer(UUID actor, UUID target) {
        Town town = administration(actor);
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
        deputies.remove(town.id(), successor);
        towns.put(town.id(), new Town(town.id(), town.name(), successor));
        setDirty();
    }
    public void leaveTown(UUID actor) {
        Town town = home(actor);
        require(!town.mayor().equals(actor), "transfer_first");
        deputies.remove(town.id(), actor);
        properties.depart(actor);
        playerTowns.remove(actor);
        setDirty();
    }
    public Town checkDisband(UUID actor) {
        Town town = mayorTown(actor);
        require(!wars.isTownInOngoingWar(town.id()), "in_active_war");
        requireNonCapital(town);
        ledger.checkClose(Ledger.Account.town(town.id()), actor);
        return town;
    }
    public void disbandTown(UUID actor) {
        Town town = checkDisband(actor);
        require(territoryLocations(town.id()).isEmpty(), "land_remaining");
        ledger.close(Ledger.Account.town(town.id()), actor);
        deputies.remove(town.id());
        descriptions.remove(town.id());
        townSpawns.remove(town.id());
        properties.disband(town.id());
        wars.onTownDisbanded(town.id());
        towns.remove(town.id());
        evaluations.remove(town.id());
        members(town.id()).forEach(properties::depart);
        playerTowns.values().removeIf(town.id()::equals);
        townNations.remove(town.id());
        townInvites.removeIf(i -> i.issuer().equals(town.id()));
        nationInvites.removeIf(i -> i.recipient().equals(town.id()));
        setDirty();
    }

    public String checkCreateNation(UUID actor, String name) {
        Town town = mayorTown(actor);
        require(!townNations.containsKey(town.id()), "already_in_nation");
        String clean = cleanName(name);
        require(findNation(clean).isEmpty(), "name_taken");
        ledger.available();
        return clean;
    }

    public Nation createNation(UUID actor, String name) {
        String clean = checkCreateNation(actor, name);
        Town town = mayorTown(actor);
        UUID id = UUID.randomUUID();
        Nation nation = new Nation(id, clean, town.id());
        nations.put(nation.id(), nation);
        nationColors.put(nation.id(), NationPalette.pickDistinct(nationColors.values(), nation.id()));
        ledger.market.bank(nation.id());
        joinNation(town, nation);
        return nation;
    }

    public int nationColor(UUID nationId) {
        if (nationId == null) return NationPalette.INDEPENDENT_COLOR;
        return nationColors.getOrDefault(nationId, NationPalette.INDEPENDENT_COLOR);
    }

    public void setNationColor(UUID actor, int rgb) {
        Nation nation = ledNation(actor);
        nationColors.put(nation.id(), rgb & 0xFFFFFF);
        setDirty();
    }

    public Town renameTown(UUID actor, String newName) {
        Town town = mayorTown(actor);
        String clean = cleanName(newName);
        require(findTown(clean).map(t -> t.id().equals(town.id())).orElse(true), "name_taken");
        Town updated = new Town(town.id(), clean, town.mayor());
        towns.put(town.id(), updated);
        setDirty();
        return updated;
    }

    public Nation renameNation(UUID actor, String newName) {
        Nation nation = ledNation(actor);
        String clean = cleanName(newName);
        require(findNation(clean).map(n -> n.id().equals(nation.id())).orElse(true), "name_taken");
        Nation updated = new Nation(nation.id(), clean, nation.capitalTown());
        nations.put(nation.id(), updated);
        setDirty();
        return updated;
    }

    public Nation setCapital(UUID actor, String townName) {
        Nation nation = ledNation(actor);
        Town target = namedTown(townName);
        require(nation.id().equals(townNations.get(target.id())), "not_member_town");
        Nation updated = new Nation(nation.id(), nation.name(), target.id());
        nations.put(nation.id(), updated);
        wars.ongoingWarsOfNation(nation.id()).forEach(w -> w.peaceDraft.resetApprovals());
        setDirty();
        return updated;
    }
    private void joinNation(Town town, Nation nation) {
        townNations.put(town.id(), nation.id());
        nationInvites.removeIf(i -> i.recipient().equals(town.id()));
        setDirty();
    }
    public Nation inviteTown(UUID actor, Town target) {
        Nation nation = ledNation(actor);
        require(!wars.isNationInOngoingWar(nation.id()), "in_active_war");
        require(!townNations.containsKey(target.id()), "already_in_nation");
        require(nationInvites.add(new Invitation(nation.id(), target.id())), "already_invited");
        setDirty();
        return nation;
    }
    public void answerNation(UUID actor, Nation nation, boolean accept) {
        Town town = mayorTown(actor);
        require(!townNations.containsKey(town.id()), "already_in_nation");
        if (accept) {
            require(!wars.isNationInOngoingWar(nation.id()), "in_active_war");
            require(!wars.isTownInOngoingWar(town.id()), "in_active_war");
        }
        require(nationInvites.remove(new Invitation(nation.id(), town.id())), "no_invitation");
        if (accept) joinNation(town, nation);
        setDirty();
    }
    private void requireNonCapital(Town town) {
        require(getTownNation(town.id()).map(n -> !n.capitalTown().equals(town.id())).orElse(true), "capital");
    }
    public void leaveNation(UUID actor) {
        Town town = mayorTown(actor);
        Nation nation = country(actor);
        require(!wars.isNationInOngoingWar(nation.id()), "in_active_war");
        requireNonCapital(town);
        townNations.remove(town.id());
        setDirty();
    }
    public void disbandNation(UUID actor) {
        Nation nation = ledNation(actor);
        require(!wars.isNationInOngoingWar(nation.id()), "in_active_war");
        trade.available();
        ledger.close(Ledger.Account.nation(nation.id()), actor);
        trade.disband(nation.id());
        wars.onNationDisbanded(nation.id());
        nations.remove(nation.id());
        nationColors.remove(nation.id());
        nationSpawns.remove(nation.id());
        townNations.values().removeIf(nation.id()::equals);
        nationInvites.removeIf(i -> i.issuer().equals(nation.id()));
        setDirty();
    }

    /** Proposes or accepts a mutual diplomatic alliance between actor's nation and target nation. */
    public boolean allyNation(UUID actor, Nation target) {
        Nation own = ledNation(actor);
        require(!own.id().equals(target.id()), "same_nation");
        require(wars.findOngoingWarBetween(own.id(), target.id(), false).isEmpty(), "at_war");
        return wars.proposeOrAcceptAlliance(own.id(), target.id());
    }

    /** Dissolves an alliance or pending alliance offer between actor's nation and target nation. */
    public void unallyNation(UUID actor, Nation target) {
        Nation own = ledNation(actor);
        require(!own.id().equals(target.id()), "same_nation");
        require(wars.removeAlliance(own.id(), target.id()), "not_allied");
    }
    static void require(boolean condition, String key) {
        if (!condition) throw new RuleViolation(key);
    }
}
