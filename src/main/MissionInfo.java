package main;

import java.util.HashMap;
import java.util.Map;

// Hilfsklasse
class MissionInfo {
    boolean foundMission = false;
    String missionId, missionName, startZeit, duration;
    Map<String,String> idToSynonym = new HashMap<>();
    Map<String,String> teamNames  = Map.of("0","Earth","1","Crystal","2","Fire","3","Ice");
}