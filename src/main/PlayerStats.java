package main;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class PlayerStats {
    public String id, name, team;
    public int passes=0, steals=0, blocks=0, wurdeGeblockt=0,
               goals=0, klaerungen=0, misses=0, vorlagen=0;
    public long ballbesitzMillis=0;
    public List<Integer> toreZeiten = new ArrayList<>();
    public List<Integer> klaerungenZeiten = new ArrayList<>();
    public long lastBallStart = -1;

    public JSONObject toJSON() {
        JSONObject o = new JSONObject();
        o.put("spielerId", id);
        o.put("spielerName", name);
        o.put("team", team);
        o.put("passe", passes);
        o.put("steals", steals);
        o.put("blocks", blocks);
        o.put("wurdeGeblockt", wurdeGeblockt);
        o.put("tore", goals);
        o.put("klaerungen", klaerungen);
        o.put("misses", misses);
        o.put("vorlagen", vorlagen);
        o.put("ballbesitz", ballbesitzMillis/1000);
        o.put("toreZeiten", toreZeiten);
        o.put("klaerungenZeiten", klaerungenZeiten);
        return o;
    }
}