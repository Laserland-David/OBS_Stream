package main;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class MissionInfo {
    boolean foundMission = false;
    String missionId, missionName, startZeit, duration;
    Map<String,String> idToSynonym = new HashMap<>();
    Map<String,String> teamNames  = Map.of("0","Earth","1","Crystal","2","Fire","3","Ice");

    // Statische Factory-Methode zum Erstellen eines MissionInfo-Objekts aus Zeilen
    public static MissionInfo getInfosFromLines(List<String> lines) {
        MissionInfo mi = new MissionInfo();
        for (String raw : lines) {
            String[] p = raw.split("\t", -1);
            if (!mi.foundMission && "1".equals(p[0]) && p.length > 1 && !p[1].startsWith("#")) {
                mi.missionId   = p[1];
                mi.missionName = p[2];
                mi.startZeit   = p[3];
                mi.duration    = p.length > 4 ? p[4] : "";
                mi.foundMission= true;
            }
            if ("1".equals(p[0]) && p.length > 1 && p[1].startsWith("#")) {
                String syn = p.length > 4 ? p[4] : (p.length > 3 ? p[3] : p[1]);
                mi.idToSynonym.put(p[1], syn);
            }
        }
        return mi;
    }
}
