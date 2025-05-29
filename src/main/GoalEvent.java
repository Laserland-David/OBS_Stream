package main;

import org.json.JSONObject;

public class GoalEvent {
    public int zeit;
    public String spielerId, spielerName, team;
    public String vorlagenSpielerId="", vorlagenSpielerName="";
    public String stealFromSpielerId="", stealFromSpielerName="";

    public JSONObject toJSON() {
        JSONObject o = new JSONObject();
        o.put("zeit", zeit);
        o.put("spielerId", spielerId);
        o.put("spielerName", spielerName);
        o.put("team", team);
        if (!vorlagenSpielerId.isEmpty()) {
            o.put("vorlagenSpielerId", vorlagenSpielerId);
            o.put("vorlagenSpielerName", vorlagenSpielerName);
        }
        if (!stealFromSpielerId.isEmpty()) {
            o.put("stealFromSpielerId", stealFromSpielerId);
            o.put("stealFromSpielerName", stealFromSpielerName);
        }
        return o;
    }
}