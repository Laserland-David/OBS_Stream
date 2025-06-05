package main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public class GameState {

    // === globaler Zustand für Live-Parsing ===
    public volatile boolean initialized = false;
    public final List<String> prebuffer = Collections.synchronizedList(new ArrayList<>());
    public MissionInfo mission;
    public final Map<String, PlayerStats> playerMap = new HashMap<>();
    public final Map<String, List<PlayerStats>> teams = new HashMap<>();
    public final List<GoalEvent> goals = new ArrayList<>();

    // === STATE-Map für Ballbesitz & Co. ===
    public final Map<String,Object> STATE = new HashMap<>();
    public final String KEY_CUR         = "__cur__";
    public final String KEY_TS          = "__lastTs__";
    public final String KEY_LAST_PASS   = "__lastPass__";
    public final String KEY_SECOND_LAST_PASS  = "__lastStealFrom__";
    public final String KEY_LAST_CLEAR  = "__lastClear__";
    public final String KEY_LAST_STEAL  = "__lastSteal__";
    public final String KEY_STEAL_FROM  = "__lastStealFrom__";
    
    

    public volatile String lastJsonFilePath = null;

    // Aktueller Ballhalter
    public volatile String currentBallHolder = null;

    // Archivierungsstatus
    public volatile boolean archived = false;

    public volatile boolean gameActive = false; // True, wenn Spiel läuft
    public volatile boolean lastGameActiveState = false;
    public volatile boolean rundeAbgebrochen = false;

    // Zeit der letzten Ballbesitz-Aktualisierung
    public volatile long lastBallbesitzUpdate = System.currentTimeMillis();

    // === Methoden ===

    /**
     * Verarbeitet jede eingehende Zeile (Teildaten-Feed).
     */
    public void handleLine(String raw) {
        if (!initialized) {
            prebuffer.add(raw);
        }
        String[] p = raw.split("\t", -1);

        // --- Erst-Init: Code "9" ---
        if (!initialized && p.length > 0 && "9".equals(p[0])) {
            mission = MissionInfo.getInfosFromLines(prebuffer);
            registerPlayers(prebuffer, mission.idToSynonym, mission.teamNames, playerMap, false);
            prebuffer.stream()
                     .filter(l -> l.startsWith("4\t"))
                     .forEach(this::processEventLine);
            prebuffer.clear();
            initialized = true;
            gameActive = true;
            System.out.println(">>> INITIALISIERT nach Code 9");
        }

        // --- Nachträgliches Hinzufügen neuer Spieler (Code "3") ---
        if ("3".equals(p[0]) && mission != null) {
            registerPlayers(Collections.singletonList(raw),
                            mission.idToSynonym,
                            mission.teamNames,
                            playerMap,
                            true);
        }

        // --- Live-Event-Zeilen (Code "4\t...") ---
        if (initialized && raw.startsWith("4\t")) {
            processEventLine(raw);
        }

        // --- Player-Status-Updates (Code "9\t...") ---
        if ("9".equals(p[0]) && p.length > 2) {
            checkPlayerState(p);
            updateJsonNow();
        }
    }

    /**
     * Liest den Status (0/2/3) eines Spielers aus dem "9\t..."-Event.
     */
    private void checkPlayerState(String[] p) {
        String playerId = p[2];
        int status = Integer.parseInt(p[3]);
        PlayerStats ps = playerMap.get(playerId);
        if (ps != null) {
            ps.currentStatus = status;
        } else {
            // Spieler existiert noch nicht, ggf. vorinitialisieren
            ps = new PlayerStats();
            ps.id = playerId;
            ps.currentStatus = status;
            playerMap.put(playerId, ps);
        }
    }

    /**
     * Schreibt die JSON sofort, z.B. nach Status-Änderung.
     */
    private void updateJsonNow() {
        JSONObject js = aggregateAndBuildJson(mission, playerMap, teams, goals);
        try {
            FileUtils.writeJsonToFile(this, js);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Registriert Spieler-Infos aus den Zeilen „3\t...“.
     * 
     * @param lines        Liste der Rohdatenzeilen
     * @param idToSynonym  Map ID→Name
     * @param teamNames    Map TeamID→Teamname
     * @param outMap       Zielmap für PlayerStats
     * @param onlyIfAbsent Wenn true, nur neu hinzufügen, falls nicht schon enthalten
     */
    public void registerPlayers(
            List<String> lines,
            Map<String,String> idToSynonym,
            Map<String,String> teamNames,
            Map<String,PlayerStats> outMap,
            boolean onlyIfAbsent
    ) {
        for (String raw : lines) {
            String[] p = raw.split("\t", -1);
            if ("3".equals(p[0]) && p.length >= 7
                    && (p[2].startsWith("@") || p[2].startsWith("#"))
                    && "Player".equalsIgnoreCase(p[3])) {
                String id = p[2];
                if (!onlyIfAbsent || !outMap.containsKey(id)) {
                    String name = idToSynonym.getOrDefault(id, p[4]);
                    String team = teamNames.getOrDefault(p[5], "Unbekannt");

                    PlayerStats ps = outMap.computeIfAbsent(id, k -> {
                        PlayerStats x = new PlayerStats();
                        x.id = id;
                        x.name = name;
                        x.team = team;
                        return x;
                    });

                    List<PlayerStats> teamPlayers = teams.computeIfAbsent(team, k -> new ArrayList<>());
                    if (!teamPlayers.contains(ps)) {
                        teamPlayers.add(ps);
                    }

                    if (onlyIfAbsent) {
                        System.out.println("Nachträglich Spieler registriert: " + name + " (" + id + ")");
                    }
                }
            }
        }
    }


    /**
     * Verarbeitet jede Event-Zeile, die mit "4\t" beginnt.
     */
    public void processEventLine(String raw) {
        String[] p       = raw.split("\t", -1);
        String   code    = p[2];
        long     eventTs = Long.parseLong(p[1]);

        long now    = System.currentTimeMillis();
        long lastTs = (Long) STATE.getOrDefault(KEY_TS, -1L);

        System.out.println(raw);

        // --- 1) Ballwechsel‐Events (Pass/Steal/Clear) ---
        if (Set.of("1100","1103","1107","1109").contains(code)) {
            String next = null;

            // Nur Timestamp updaten – Ballbesitzzeit rechnet der Writer-Thread!
            STATE.put(KEY_TS, now);

            switch (code) {
                case "1100": // Pass
                    if (p.length >= 6) {
                        String giver = p[3], recv = p[5];
                        Optional.ofNullable(playerMap.get(giver)).ifPresent(ps -> ps.passes++);
                        Optional.ofNullable(playerMap.get(recv)).ifPresent(ps -> ps.passeErhalten++);
                        
                        Object prevLastPass = STATE.get(KEY_LAST_PASS);
                        if (prevLastPass != null) {
                            STATE.put(KEY_SECOND_LAST_PASS, prevLastPass);
                        }
                        
                        STATE.put(KEY_LAST_PASS, giver);
                        STATE.remove(KEY_LAST_CLEAR);
                        STATE.remove(KEY_LAST_STEAL);
                        STATE.remove(KEY_STEAL_FROM);
                        next = recv;
                    }
                    break;

                case "1103": // Steal
                    if (p.length >= 6) {
                        String st     = p[3], stFrom = p[5];
                        Optional.ofNullable(playerMap.get(st)).ifPresent(ps -> ps.steals++);
                        Optional.ofNullable(playerMap.get(stFrom)).ifPresent(ps -> ps.wurdeGestealed++);
                        STATE.put(KEY_LAST_STEAL, st);
                        STATE.put(KEY_STEAL_FROM, stFrom);
                        STATE.remove(KEY_LAST_PASS);
                        STATE.remove(KEY_LAST_CLEAR);
                        next = st;
                    }
                    break;

                case "1107": // Ball erhalten (kein Stat-Update)
                    next = p[3];
                    STATE.remove(KEY_LAST_PASS);
                    STATE.remove(KEY_LAST_CLEAR);
                    STATE.remove(KEY_LAST_STEAL);
                    STATE.remove(KEY_STEAL_FROM);
                    break;

                case "1109": // Clear
                    if (p.length >= 6) {
                        String clr  = p[3], recv = p[5];
                        Optional.ofNullable(playerMap.get(clr)).ifPresent(ps -> ps.klaerungen++);
                        STATE.put(KEY_LAST_CLEAR, clr);
                        next = recv;

                        // --- Vergeblicher Angriff ---
                        //Von wem wurde der Ball vor einem Clear gestohlen?
                        String lastThief = (String) STATE.get(KEY_STEAL_FROM);
                        if (lastThief != null && !lastThief.equals(clr)) {
                            PlayerStats angreifer = playerMap.get(lastThief);
                            PlayerStats klaerer   = playerMap.get(clr);
                            if (angreifer != null && klaerer != null) {
                                angreifer.vergeblicherAngriff++;
                            }
                        }
                        // Nach einem Clear ist diese Angriffs-Chain beendet:
                        STATE.remove(KEY_LAST_STEAL);
                        STATE.remove(KEY_STEAL_FROM);
                        STATE.remove(KEY_LAST_PASS);
                    }
                    break;
            }

            // Falls neuer Ballhalter feststeht, setzen wir ihn auch:
            if (next != null) {
                PlayerStats neu = playerMap.get(next);
                if (neu != null) {
                    neu.lastBallStart = now;
                    STATE.put(KEY_CUR, next);
                    currentBallHolder = next;
                } else {
                    System.err.println("Warnung: Spieler \"" + next + "\" nicht gefunden.");
                }
            }

            lastBallbesitzUpdate = now;  // Timer‐Reset
            return;
        }

        // --- 2) Tor-Logik (1101) mit Vorlagen + Ballbesitz beenden ---
        if ("1101".equals(code) && p.length >= 4) {
            String scorer = p[3];
            PlayerStats sc = playerMap.get(scorer);
            if (sc != null) {
                sc.goals++;
                long missionStart = Long.parseLong(mission.startZeit);
                int sec = (int)((eventTs - missionStart) / 1000);
                sc.toreZeiten.add(sec);

                GoalEvent ge = new GoalEvent();
                ge.zeit        = sec;
                ge.spielerId   = scorer;
                ge.spielerName = sc.name;
                ge.team        = sc.team;

                if (STATE.containsKey(KEY_LAST_PASS)) {
                    String lp = (String) STATE.get(KEY_LAST_PASS);
                    ge.vorlagenSpielerId   = lp;
                    ge.vorlagenSpielerName = playerMap.get(lp).name;
                    playerMap.get(lp).vorlagen++;
                } else if (STATE.containsKey(KEY_LAST_CLEAR)) {
                    String lc = (String) STATE.get(KEY_LAST_CLEAR);
                    ge.vorlagenSpielerId   = lc;
                    ge.vorlagenSpielerName = playerMap.get(lc).name;
                    playerMap.get(lc).vorlagen++;
                } else if (STATE.containsKey(KEY_LAST_STEAL)) {
                    String stFrom = (String) STATE.get(KEY_STEAL_FROM);
                    ge.stealFromSpielerId   = stFrom;
                    ge.stealFromSpielerName = playerMap.get(stFrom).name;
                }
                goals.add(ge);
            }

            // Ballbesitz-Rest aufsummieren:
            String ownerAfterGoal = (String) STATE.get(KEY_CUR);
            if (ownerAfterGoal != null && lastTs > 0) {
                Optional.ofNullable(playerMap.get(ownerAfterGoal)).ifPresent(ps -> {
                    long diff = now - lastTs;
                    if (diff > 0) ps.ballbesitzMillis += diff;
                });
            }

            // STATE zurücksetzen
            STATE.remove(KEY_CUR);
            STATE.put(KEY_TS, now);
            STATE.remove(KEY_LAST_PASS);
            STATE.remove(KEY_LAST_CLEAR);
            STATE.remove(KEY_LAST_STEAL);
            STATE.remove(KEY_STEAL_FROM);
            currentBallHolder = null;

            lastBallbesitzUpdate = now;
            return;
        }

        // --- 3) Miss (0201) ---
        if ("0201".equals(code) && p.length >= 4) {
            playerMap.getOrDefault(p[3], new PlayerStats()).misses++;
            return;
        }

        // --- 4) Block (1104) ---
        if ("1104".equals(code) && p.length >= 6) {
            PlayerStats b = playerMap.get(p[3]);
            PlayerStats t = playerMap.get(p[5]);
            if (b != null && t != null) {
                b.blocks++;
                t.wurdeGeblockt++;
                // Block im Reset (Target im Reset)
                if (t.currentStatus == 2) {
                    b.blocksImReset++;
                    t.wurdeImResetGeblockt++;
                }
            }
            return;
        }

        // --- 5) Spielende (0101) ---
        if ("0101".equals(code)) {
            String owner = (String) STATE.get(KEY_CUR);
            if (owner != null && lastTs > 0) {
                Optional.ofNullable(playerMap.get(owner)).ifPresent(ps -> {
                    long diff = now - lastTs;
                    if (diff > 0) ps.ballbesitzMillis += diff;
                });
            }

            STATE.remove(KEY_CUR);
            STATE.put(KEY_TS, now);
            STATE.remove(KEY_LAST_PASS);
            STATE.remove(KEY_LAST_CLEAR);
            STATE.remove(KEY_LAST_STEAL);
            STATE.remove(KEY_STEAL_FROM);
            currentBallHolder = null;

            lastBallbesitzUpdate = now;
            gameActive = false;
            rundeAbgebrochen = false;  // reguläres Ende
            
        }
    }

    /**
     * Baut das finale JSON‐Objekt zusammen (Spieler, Teams, Tore, etc.).
     */
    public JSONObject aggregateAndBuildJson(
            MissionInfo mi,
            Map<String,PlayerStats> pm,
            Map<String,List<PlayerStats>> tm,
            List<GoalEvent> gl
    ) {
        JSONObject out = new JSONObject();
        out.put("spielId", "Spieldaten");
        out.put("missionId", mi.missionId);
        out.put("missionName", mi.missionName);
        out.put("startZeit", mi.startZeit);
        out.put("duration", mi.duration);
        out.put("gameActive", gameActive);

     // NEU: Spielstatus als String
        String spielStatus = "running";
        if (!gameActive && rundeAbgebrochen) spielStatus = "abgebrochen";
        else if (!gameActive && !rundeAbgebrochen) spielStatus = "beendet";

        out.put("spielStatus", spielStatus);
        
        JSONObject all = new JSONObject();
        tm.forEach((team, list) -> {
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();

            int bp = 0, t = 0, v = 0, bl = 0, gb = 0, mi2 = 0, kl = 0, ps = 0, st = 0;
            int blocksImResetSum = 0, wurdeImResetGeblocktSum = 0, vergeblicherAngriff = 0;

            for (PlayerStats p : list) {
                arr.put(p.toJSON());
                bp += p.ballbesitzMillis / 1000;
                t  += p.goals;       v  += p.vorlagen;
                bl += p.blocks;      gb += p.wurdeGeblockt;
                mi2+= p.misses;      kl += p.klaerungen;
                ps += p.passes;      st += p.steals;
                blocksImResetSum       += p.blocksImReset;
                wurdeImResetGeblocktSum+= p.wurdeImResetGeblockt;
                vergeblicherAngriff    += p.vergeblicherAngriff;
            }
            o.put("spieler", arr);
            o.put("gesamtBallbesitz", bp);
            o.put("vergeblicherAngriff", vergeblicherAngriff);
            o.put("tore", t);
            o.put("vorlagen", v);
            o.put("blocks", bl);
            o.put("wurdeGeblockt", gb);
            o.put("blocksImReset", blocksImResetSum);
            o.put("wurdeImResetGeblockt", wurdeImResetGeblocktSum);
            o.put("misses", mi2);
            o.put("klaerungen", kl);
            o.put("passe", ps);
            o.put("steals", st);

            all.put(team, o);
        });
        out.put("teams", all);

        JSONArray ga = new JSONArray();
        gl.forEach(g -> ga.put(g.toJSON()));
        out.put("tore", ga);

        // Aktuellen Ballhalter mit Namen einfügen
        if (currentBallHolder != null && playerMap.containsKey(currentBallHolder)) {
            out.put("aktuellerBallhalter", playerMap.get(currentBallHolder).name);
        } else {
            out.put("aktuellerBallhalter", JSONObject.NULL);
        }

        return out;
    }

    /** Setzt den Spielzustand zurück für die nächste Session. */
    public void resetForNextSession() {
        this.initialized = false;
        this.playerMap.clear();
        this.teams.clear();
        this.goals.clear();
        this.prebuffer.clear();
        this.STATE.clear();
        this.currentBallHolder = null;
        this.archived = false;
        this.gameActive = false;
    }

    /** Rechnet die Ballbesitz‐Zeit für den aktuellen Ballhalter hoch. */
    public void recalculateBallPossession() {
        long now = System.currentTimeMillis();
        if (currentBallHolder != null) {
            PlayerStats ps = playerMap.get(currentBallHolder);
            if (ps != null) {
                long diff = now - lastBallbesitzUpdate;
                if (diff > 0) ps.ballbesitzMillis += diff;
            }
        }
        lastBallbesitzUpdate = now;
    }
}
