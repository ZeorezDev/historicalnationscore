package dev.zeorez.historicalnations.core;

import com.mojang.authlib.GameProfile;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.gui.CityNetwork.Query;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class CityPanel {
    public static CompoundTag page(ServerPlayer player, Query q) {
        if (!player.hasPermissions(4)) throw new RuleViolation("city_permission");
        if (q.page()<0 || q.size()<1 || q.size()>8 || q.history()<0 || q.stars() < -1 || q.stars()>5
                || q.level()<0 || q.level()>5) throw new RuleViolation("city_invalid");
        WorldRegistry data = WorldRegistry.get(player.server);
        var counts = PoliticalPanel.counts(player,data);
        if (q.town()!=null) {
            var town = data.getTownById(q.town()).orElseThrow(()->new RuleViolation("unknown_town"));
            var out = row(player,data,town,counts);
            var history = data.evaluations.getOrDefault(town.id(),List.of());
            out.putInt("revision",history.size());
            if (!history.isEmpty()) {
                int index = q.history()==0 ? history.size() : Math.min(q.history(),history.size());
                out.put("review",history.get(index-1).fields(true));
                if (q.history()>0) out.putInt("history",index);
            }
            return out;
        }
        var names = Comparator.comparing(Town::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Town::id);
        Comparator<Town> order = switch(q.sort()) {
            case NAME -> names;
            case RECENT -> Comparator.<Town>comparingLong(t->{
                var e=data.evaluation(t.id()); return e==null?0:e.date();
            }).reversed().thenComparing(names);
            case SCORE -> Comparator.<Town>comparingInt(t->{
                var e=data.evaluation(t.id()); return e==null?-1:e.draft().total();
            }).reversed().thenComparing(names);
        };
        var filter=q.name().toLowerCase(Locale.ROOT);
        var nationFilter=q.nation().toLowerCase(Locale.ROOT);
        var towns=data.towns.values().stream().filter(t->{
            if (!t.name().toLowerCase(Locale.ROOT).contains(filter)) return false;
            var nation=data.getTownNation(t.id());
            if (!nation.map(n->n.name().toLowerCase(Locale.ROOT)).orElse("independent").contains(nationFilter)) return false;
            var e=data.evaluation(t.id());
            return (q.level()==0 || q.level()==(e==null?1:e.draft().level()))
                    && (q.stars()<0 || q.stars()==(e==null?0:e.draft().rating()));
        }).sorted(order).toList();
        int pages=Math.max(1,(towns.size()+q.size()-1)/q.size()), page=Math.min(q.page(),pages-1);
        var rows=new ListTag();
        for (int i=page*q.size(); i<Math.min(towns.size(),(page+1)*q.size()); i++) rows.add(row(player,data,towns.get(i),counts));
        var out=new CompoundTag();
        out.put("rows",rows); out.putInt("pages",pages); out.putInt("page",page); out.putInt("size",q.size());
        return out;
    }
    private static CompoundTag row(ServerPlayer player, WorldRegistry data, Town town, PoliticalPanel.Counts counts) {
        var r=new CompoundTag();
        r.putUUID("town",town.id()); r.putString("name",town.name());
        data.getTownNation(town.id()).ifPresent(n->r.putString("nation",n.name()));
        r.putString("mayor",player.server.getProfileCache().get(town.mayor()).map(GameProfile::getName).orElse(town.mayor().toString()));
        r.putInt("population",counts.people().getOrDefault(town.id(),0));
        r.putInt("claims",counts.chunks().getOrDefault(town.id(),0));
        var review=data.evaluation(town.id());
        r.putInt("level",review==null?1:review.draft().level());
        r.putInt("rating",review==null?0:review.draft().rating());
        r.putInt("score",review==null?-1:review.draft().total());
        if (review!=null) r.putLong("date",review.date());
        return r;
    }
}
