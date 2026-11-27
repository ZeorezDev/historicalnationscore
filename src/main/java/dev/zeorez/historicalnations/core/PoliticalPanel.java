package dev.zeorez.historicalnations.core;

import com.mojang.authlib.GameProfile;
import dev.zeorez.historicalnations.api.*;
import dev.zeorez.historicalnations.gui.PanelQuery;
import dev.zeorez.historicalnations.integration.TownTerritories;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import java.math.*;
import java.util.*;
import java.util.function.Function;
import static dev.zeorez.historicalnations.gui.PanelQuery.View;
import static dev.zeorez.historicalnations.core.MonetaryPolicy.REF;

/** Builds a bounded page on the server thread. Raw registry and bank records never cross the wire. */
public final class PoliticalPanel {
    private final ServerPlayer viewer;
    private final WorldRegistry data;
    private final PanelQuery q;
    private final CompoundTag out = new CompoundTag();
    private final ListTag rows = new ListTag();
    record Counts(int tick, Map<UUID,Integer> people, Map<UUID,Integer> chunks) {}
    private Map<UUID,Integer> population, land;
    private PoliticalPanel(ServerPlayer viewer, PanelQuery query) {
        this.viewer=viewer; this.q=query; data=WorldRegistry.get(viewer.server);
        out.put("rows",rows);
    }
    public static CompoundTag page(ServerPlayer viewer, PanelQuery q) {
        var panel=new PoliticalPanel(viewer,q);
        panel.build();
        return panel.out;
    }
    private String name(UUID player) {
        return viewer.server.getProfileCache().get(player).map(GameProfile::getName).orElse(player.toString());
    }
    private String role(Town t, UUID actor) {
        if (actor.equals(t.mayor())) return "@mayor";
        return actor.equals(data.deputy(t.id())) ? "@deputy" : "@citizen";
    }
    private static CompoundTag row(String... values) {
        var r=new CompoundTag(); var cells=new ListTag();
        for (String v:values) cells.add(StringTag.valueOf(v));
        r.put("cells",cells); return r;
    }
    private static CompoundTag link(CompoundTag row, View view, UUID id) {
        row.putString("view",view.name()); row.putUUID("id",id); return row;
    }
    private void field(String key,String value) { rows.add(row("@"+key,value)); }
    private void title(String value) { out.putString("title",value); }
    private void counts() {
        Counts counts = counts(viewer, data);
        population = counts.people();
        land = counts.chunks();
    }
    static Counts counts(ServerPlayer viewer, WorldRegistry data) {
        int tick=viewer.server.getTickCount();
        Counts cached=data.directoryCounts;
        if (cached!=null && cached.tick()==tick) {
            return cached;
        }
        Map<UUID,Integer> population=new HashMap<>(), land=new HashMap<>();
        data.playerTowns.values().forEach(id->population.merge(id,1,Integer::sum));
        var territories=new TownTerritories(viewer.server);
        for (var pos:data.territoryLocations()) {
            var t=territories.verified(pos);
            if (t!=null) land.merge(t.town(),1,Integer::sum);
        }
        return data.directoryCounts=new Counts(tick,population,land);
    }
    private String funds(Ledger.Account bank, String code) { return data.ledger.balance(bank.in(code))+" "+code; }
    private void build() {
        UUID actor=viewer.getUUID();
        var home=data.getPlayerTown(actor).orElse(null);
        var country=data.getPlayerNation(actor).orElse(null);
        if (q.view()==View.HOME || q.view()==View.PLAYER) {
            title(q.view()==View.HOME ? "@home" : viewer.getGameProfile().getName());
            field("town",home==null ? "@no_town" : home.name());
            field("nation",country==null ? "@no_nation" : country.name());
            if (q.view()==View.HOME) {
                out.putInt("townCount",data.towns.size());
                out.putInt("nationCount",data.nations.size());
                out.putInt("warCount",data.wars.ongoingWars().size());
            }
            if (q.view()==View.PLAYER) {
                field("town_rank",home==null ? "@none" : role(home,actor));
                field("nation_rank",country==null ? "@none" : data.isNationLeader(actor) ? "@leader" : "@citizen");
                field("wallet",funds(Ledger.Account.player(actor),data.ledger.market.official(country==null?null:country.id())));
            }
            return;
        }
        if (q.view()==View.WAR_DIPLOMACY) {
            buildWarDiplomacy(actor, country);
            return;
        }
        if (q.view()==View.NATIONS || q.view()==View.TOWNS) { directory(null); return; }
        boolean national=switch(q.view()) { case NATION, MEMBER_TOWNS, NATION_OFFICE -> true; default -> false; };
        UUID id=q.target();
        if (q.view()==View.MEMBER) {
            Town t=data.getPlayerTown(id).orElse(null);
            if (t!=null && id!=null) {
                title(name(id)); field("town",t.name()); field("town_rank",role(t,id)); field("status",online(id));
                out.putUUID("parent",t.id());
                if (!actor.equals(id) && !t.mayor().equals(id)) {
                    var allowed=OfficeActions.allowed(data,actor,t.id(),false);
                    for (var action:List.of(OfficeActions.DEPUTY,OfficeActions.MAYOR,OfficeActions.KICK)) {
                        if (allowed.contains(action)) rows.add(operation(action));
                    }
                }
            }
            return;
        }
        if (id==null) id=national ? country==null?null:country.id() : home==null?null:home.id();
        Town t=data.towns.get(id); Nation n=data.nations.get(id);
        if (national ? n==null : t==null) { title(national?"@no_nation":"@no_town"); return; }
        out.putUUID("subject",id); title(national?n.name():t.name());
        boolean office=national ? country!=null && country.id().equals(id) : home!=null && home.id().equals(id);
        out.putBoolean("office",office);
        switch(q.view()) {
            case TOWN_OFFICE, NATION_OFFICE -> {
                List<OfficeActions> actions=List.copyOf(OfficeActions.allowed(data,actor,id,national));
                slice(actions,PoliticalPanel::operation);
            }
            case RESIDENTS -> {
                var residents=data.members(id);
                slice(residents,member->{
                    var r=link(row(name(member),role(t,member),online(member)),View.MEMBER,member);
                    r.putUUID("head",member); return r;
                });
            }
            case MEMBER_TOWNS -> directory(n);
            case TOWN -> {
                var parent=data.getTownNation(id).orElse(null);
                field("mayor",name(t.mayor()));
                field("deputy",data.deputy(id)==null?"@none":name(data.deputy(id)));
                field("nation",parent==null?"@independent":parent.name());
                field("people",""+data.members(id).size()); field("land",""+new TownTerritories(viewer.server).count(id));
                field("bank",funds(Ledger.Account.town(id),data.ledger.market.official(parent==null?null:parent.id())));
                field("status",parent==null?"@independent":parent.capitalTown().equals(id)?"@capital":"@member_town");
                var review = data.evaluation(id);
                field("development", "@level_" + (review == null ? 1 : review.draft().level()));
                field("score", review == null ? "@unevaluated" : review.draft().total() + "/100");
                field("rating", review == null ? "@unrated" : CityEvaluation.stars(review.draft().rating()));
                if (review != null) {
                    field("evaluated", java.time.Instant.ofEpochMilli(review.date()).toString().substring(0, 10));
                    for (var category : CityEvaluation.Category.values())
                        field(category.name().toLowerCase(Locale.ROOT), review.draft().score(category) + "/" + category.max);
                    String summary = review.draft().summary();
                    if (!summary.isEmpty()) field("summary", summary.startsWith("@") ? "@" + summary : summary);
                }
                var tsp=data.townSpawn(id);
                field("spawn",tsp==null?"@none":tsp.blockPos().getX()+", "+tsp.blockPos().getY()+", "+tsp.blockPos().getZ());
                String description=data.description(id);
                if (!description.isEmpty()) field("description",description.startsWith("@") ? "@"+description : description);
            }
            case NATION -> {
                counts(); var capital=data.towns.get(n.capitalTown()); var towns=data.getNationTowns(id);
                field("capital",capital.name()); field("leader",name(capital.mayor())); field("towns",""+towns.size());
                field("people",""+towns.stream().mapToInt(x->population.getOrDefault(x.id(),0)).sum());
                field("land",""+towns.stream().mapToInt(x->land.getOrDefault(x.id(),0)).sum());
                var m=data.ledger.market.resolve(data.ledger.market.official(id));
                field("bank",funds(Ledger.Account.nation(id),m.code));
                field("currency",m.name.isBlank()?m.code:m.name+" ("+m.code+")");
                field("supply",data.ledger.supply(m.code)+" "+m.code);
                field("inflation",BigDecimal.valueOf(data.ledger.market.bank(id).inflation,2)+"%");
                field("exchange","1 "+m.code+" = "+MonetaryPolicy.decimal(m.quote)+" "+REF);
                field("map_color",dev.zeorez.historicalnations.map.NationPalette.formatHex(data.nationColor(id)));
                var nsp=data.nationSpawn(id);
                if(nsp==null) nsp=data.townSpawn(n.capitalTown());
                field("spawn",nsp==null?"@none":nsp.blockPos().getX()+", "+nsp.blockPos().getY()+", "+nsp.blockPos().getZ());
                var allies=data.wars.alliesOf(id).stream().map(aid->data.getNationById(aid).map(Nation::name).orElse("?")).toList();
                field("allies",allies.isEmpty()?"@none":String.join(", ",allies));
                var activeWars=data.wars.ongoingWarsOfNation(id);
                field("active_wars",activeWars.isEmpty()?"@none":""+activeWars.size());
            }
            default -> {}
        }
    }

    private void buildWarDiplomacy(UUID actor, Nation country) {
        title("@war_diplomacy");
        if (country != null) {
            out.putUUID("subject", country.id());
            out.putBoolean("office", data.isNationLeader(actor));
        }
        long nowMs = System.currentTimeMillis();
        boolean sysEnabled = dev.zeorez.historicalnations.war.WarService.isWarSystemEnabled(data);
        String winStatus = dev.zeorez.historicalnations.war.WarSchedule.describeStatus(java.time.Instant.ofEpochMilli(nowMs));
        field("war_system", sysEnabled ? "@war_enabled" : "@war_disabled_short");
        field("war_window", winStatus);
        field("active_attacks", "" + data.wars.activeFlags().size());

        if (country != null) {
            var allies = data.wars.alliesOf(country.id()).stream()
                    .map(aid -> data.getNationById(aid).map(Nation::name).orElse("?")).toList();
            field("allies", allies.isEmpty() ? "@none" : String.join(", ", allies));

            var truces = data.wars.activeTrucesFor(country.id(), nowMs);
            if (truces.isEmpty()) {
                field("truces", "@none");
            } else {
                List<String> trTxt = new ArrayList<>();
                for (var tr : truces) {
                    UUID other = tr.pair().other(country.id());
                    String oname = data.getNationById(other).map(Nation::name).orElse("?");
                    long hrs = Math.max(1L, (tr.expiresAtEpochMs() - nowMs + 3_599_999L) / 3_600_000L);
                    trTxt.add(oname + " (" + hrs + "h)");
                }
                field("truces", String.join(", ", trTxt));
            }
        }

        // Ongoing Wars list (clickable to open Peace Treaty screen!)
        for (var war : data.wars.ongoingWars()) {
            String warTitle = dev.zeorez.historicalnations.war.WarService.warTitle(data, war);
            String stateDesc = war.state.name();
            if (war.state == dev.zeorez.historicalnations.war.WarModels.WarState.PREPARING && war.preparationEndsAtEpochMs > nowMs) {
                long mins = Math.max(1L, (war.preparationEndsAtEpochMs - nowMs) / 60_000L);
                stateDesc = "PREP " + (mins / 60) + "h " + (mins % 60) + "m";
            } else {
                long durMins = Math.max(0L, (nowMs - war.declaredAtEpochMs) / 60_000L);
                stateDesc = war.state.name() + " • " + (durMins / 60) + "h " + (durMins % 60) + "m";
            }
            Set<UUID> occTownIds = new HashSet<>();
            war.occupiedChunks.values().forEach(oc -> occTownIds.add(oc.originalTownId()));
            int activeFronts = data.wars.activeFlagsOfWar(war.warId).size();
            String summary = stateDesc
                    + " | Occ: " + war.occupiedChunks.size() + " (" + occTownIds.size() + " towns)"
                    + " | Flags: " + activeFronts
                    + (war.peaceDraft.attackerApproved || war.peaceDraft.defenderApproved ? " | [PEACE OFFER]" : "");
            CompoundTag r = row("⚔ " + warTitle, summary);
            r.putUUID("warId", war.warId);
            rows.add(r);
        }

        // Diplomatic & War actions for Nation Leaders directly inside WAR & DIPLOMACY
        if (country != null && data.isNationLeader(actor)) {
            var allowed = OfficeActions.allowed(data, actor, country.id(), true);
            for (var act : List.of(OfficeActions.DECLARE_WAR, OfficeActions.PEACE_TREATY,
                    OfficeActions.JOIN_WAR, OfficeActions.ALLY, OfficeActions.UNALLY)) {
                if (allowed.contains(act)) {
                    rows.add(operation(act));
                }
            }
        }
        out.putInt("page", 0);
        out.putInt("pages", 1);
        out.putInt("total", rows.size());
    }
    private static CompoundTag operation(OfficeActions action) {
        var r=row("@act_"+action.name().toLowerCase(Locale.ROOT));
        r.putString("action",action.name());
        return r;
    }
    private String online(UUID player) { return viewer.server.getPlayerList().getPlayer(player)==null?"@offline":"@online"; }
    private record Entry(UUID id,String name,Town seat,int people,int towns,int claims,BigInteger assets) {}
    private void directory(Nation memberOf) {
        counts();
        boolean national=q.view()==View.NATIONS;
        if (memberOf==null) title(national?"@nations":"@town_directory");
        var entries=new ArrayList<Entry>();
        String filter=q.search().toLowerCase(Locale.ROOT);
        if (national) {
            var sums=new HashMap<UUID,int[]>();
            data.townNations.forEach((town,nation)->{
                int[] total=sums.computeIfAbsent(nation,k->new int[3]);
                total[0]+=population.getOrDefault(town,0); total[1]++; total[2]+=land.getOrDefault(town,0);
            });
            var assets=data.ledger.treasuryValues();
            for (var n:data.nations.values()) {
                if (!n.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
                var values=sums.get(n.id()); var seat=data.towns.get(n.capitalTown());
                entries.add(new Entry(n.id(),n.name(),seat,values[0],values[1],values[2],assets.getOrDefault(n.id(),BigInteger.ZERO)));
            }
        } else {
            for (var t:data.towns.values()) {
                if ((memberOf!=null && !memberOf.id().equals(data.townNations.get(t.id()))) ||
                        !t.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
                entries.add(new Entry(t.id(),t.name(),t,population.getOrDefault(t.id(),0),0,land.getOrDefault(t.id(),0),BigInteger.ZERO));
            }
        }
        Comparator<Entry> names=Comparator.comparing(Entry::name,String.CASE_INSENSITIVE_ORDER);
        Comparator<Entry> order=switch(q.sort()) {
            case PEOPLE -> Comparator.comparingInt(Entry::people);
            case LAND -> Comparator.comparingInt(Entry::claims);
            case TOWNS -> national ? Comparator.comparingInt(Entry::towns) : names;
            case TREASURY -> national ? Comparator.comparing(Entry::assets) : names;
            default -> names;
        };
        entries.sort((q.descending()?order.reversed():order).thenComparing(names).thenComparing(Entry::id));
        slice(entries,e->{
            String leader=name(e.seat.mayor());
            CompoundTag r;
            if (national) r=row(e.name,e.seat.name(),leader,""+e.people,""+e.towns,""+e.claims,
                    new BigDecimal(e.assets,6).stripTrailingZeros().toPlainString());
            else {
                String affiliation=memberOf==null ? data.getTownNation(e.id).map(Nation::name).orElse("@independent")
                        : memberOf.capitalTown().equals(e.id)?"@capital":"@member_town";
                r=row(e.name,affiliation,leader,""+e.people,""+e.claims);
            }
            return link(r,national?View.NATION:View.TOWN,e.id);
        });
    }
    private <T> void slice(List<T> values,Function<T,CompoundTag> render) {
        int pages=Math.max(1,(int)Math.ceil(values.size()/(double)q.size()));
        int page=Math.min(pages-1,q.page()), from=page*q.size();
        out.putInt("page",page); out.putInt("pages",pages); out.putInt("total",values.size());
        for(int i=from;i<Math.min((long)from+q.size(),values.size());i++) rows.add(render.apply(values.get(i)));
    }
}
