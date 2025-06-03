package main;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class PlayerStats {
    public String id, name, team;
    public int passes=0, steals=0, blocks=0, wurdeGeblockt=0,
               goals=0, klaerungen=0, misses=0, vorlagen=0, currentStatus=-1,blocksImReset = 0,wurdeImResetGeblockt = 0,
               wurdeGestealed = 0, passeErhalten = 0, vergeblicherAngriff, secondAssist, unsuccessfulClear, clearErhalten;
    public long ballbesitzMillis=0;
    public List<Integer> toreZeiten = new ArrayList<>();
    public List<Integer> klaerungenZeiten = new ArrayList<>();
    public long lastBallStart = -1;

    public JSONObject toJSON() {
        JSONObject o = new JSONObject();
        o.put("spielerId", id);
        o.put("spielerName", name);
        o.put("status", currentStatus);
        o.put("team", team);
        o.put("passe", passes);
        o.put("passeErhalten", passeErhalten);
        o.put("steals", steals);
        o.put("wurdeGestealed", wurdeGestealed);
        o.put("blocks", blocks);
        o.put("wurdeGeblockt", wurdeGeblockt);
        o.put("blocksImReset", blocksImReset);
        o.put("wurdeImResetGeblockt", wurdeImResetGeblockt);
        o.put("tore", goals);
        o.put("vergeblicherAngriff", vergeblicherAngriff);
        o.put("klaerungen", klaerungen);
        o.put("unsuccessfulClear", unsuccessfulClear);
        o.put("clearErhalten", clearErhalten);
        o.put("misses", misses);
        o.put("vorlagen", vorlagen);
        o.put("secondAssist", secondAssist);
        o.put("ballbesitz", ballbesitzMillis/1000);
        o.put("toreZeiten", toreZeiten);
        o.put("klaerungenZeiten", klaerungenZeiten);
        return o;
    }
}