package main;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * Live-TDF-Parser:
 * - Puffer bis Code "9" → Initialisierung (MissionInfo + Players)
 * - Dann alle 4-Zeilen live verarbeiten
 * - Jede Sekunde JSON aktualisieren
 */
public class Main {

    // === globaler Zustand für Live-Parsing ===
    private static volatile boolean initialized = false;
    private static final List<String> prebuffer = Collections.synchronizedList(new ArrayList<>());
    private static MissionInfo mission;
    private static final Map<String, PlayerStats> playerMap = new HashMap<>();
    private static final Map<String, List<PlayerStats>> teams = new HashMap<>();
    private static final List<GoalEvent> goals = new ArrayList<>();

    // === STATE-Map für Ballbesitz & Co. ===
    private static final Map<String,Object> STATE = new HashMap<>();
    private static final String KEY_CUR        = "__cur__";
    private static final String KEY_TS         = "__lastTs__";
    private static final String KEY_LAST_PASS  = "__lastPass__";
    private static final String KEY_LAST_CLEAR = "__lastClear__";
    private static final String KEY_LAST_STEAL = "__lastSteal__";
    private static final String KEY_STEAL_FROM = "__lastStealFrom__";

    // Aktueller Ballhalter
    private static volatile String currentBallHolder = null;

    // Pfad der aktuell geschriebenen JSON-Datei (für Archivierung)
    private static volatile String lastJsonFilePath = null;
    private static volatile boolean archived = false;

    private static volatile long lastBallbesitzUpdate = System.currentTimeMillis();
    
    public static void main(String[] args) throws Exception {
        Thread writer = new Thread(() -> {
            try {
                while (!initialized) Thread.sleep(100);
                while (true) {
                    long now = System.currentTimeMillis();
                    if (currentBallHolder != null) {
                        PlayerStats ps = playerMap.get(currentBallHolder);
                        if (ps != null) {
                            long diff = now - lastBallbesitzUpdate;
                            if (diff > 0) {
                                ps.ballbesitzMillis += diff;
                            }
                        }
                    }
                    lastBallbesitzUpdate = now;

                    JSONObject js = aggregateAndBuildJson(mission, playerMap, teams, goals);
                    writeJsonToFile(js);
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "JSON-Writer");
        writer.setDaemon(true);
        writer.start();

        // TCP-Server starten (dein existierender Code)
        listenTcpAndProcess(7001);
    }

    /** TCP-Server: jede empfangene Zeile an handleLine() weitergeben */
    private static void listenTcpAndProcess(int port) {
        System.out.println("Starte TCP Server auf Port " + port + "...");
        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                archived = false;
                try (Socket sock = ss.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)))
                {
                    System.out.println("Verbindung von " + sock.getInetAddress());
                    String line;
                    while ((line = in.readLine()) != null) {
                        handleLine(line.trim());
                    }
                    System.out.println("Verbindung beendet.");
                    archiveCurrentJsonFile();
                    initialized = false;
                    playerMap.clear();
                    teams.clear();
                    goals.clear();
                    prebuffer.clear();
                    STATE.clear();
                    currentBallHolder = null;
                } catch (IOException e) {
                    System.err.println("Fehler bei Verbindung: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server-Fehler: " + e.getMessage());
        }
    }

    /** Puffer + Init bei erstem Code "9" + danach Live-Parse aller "4\t" */
    private static void handleLine(String raw) {
        if (!initialized) prebuffer.add(raw);
        String[] p = raw.split("\t", -1);

        // Erst-Init: Code "9"
        if (!initialized && p.length > 0 && "9".equals(p[0])) {
            mission = loadMissionInfoAndSynonymsFromLines(prebuffer);
            registerPlayersFromLines(prebuffer, mission.idToSynonym, mission.teamNames, playerMap);
            prebuffer.stream().filter(l->l.startsWith("4\t"))
                     .forEach(Main::processEventLine);
            prebuffer.clear();
            initialized = true;
            System.out.println(">>> INITIALISIERT nach Code 9");
        }

        // danach: jede 4-Event-Zeile live parsen
        if (initialized && raw.startsWith("4\t")) {
            processEventLine(raw);
        }
    }

    private static MissionInfo loadMissionInfoAndSynonymsFromLines(List<String> lines) {
        MissionInfo mi = new MissionInfo();
        for (String raw : lines) {
            String[] p = raw.split("\t", -1);
            if (!mi.foundMission && "1".equals(p[0]) && p.length>1 && !p[1].startsWith("#")) {
                mi.missionId   = p[1];
                mi.missionName = p[2];
                mi.startZeit   = p[3];
                mi.duration    = p.length>4 ? p[4] : "";
                mi.foundMission= true;
            }
            if ("1".equals(p[0]) && p.length>1 && p[1].startsWith("#")) {
                String syn = p.length>4 ? p[4] : (p.length>3 ? p[3] : p[1]);
                mi.idToSynonym.put(p[1], syn);
            }
        }
        return mi;
    }

    private static void registerPlayersFromLines(
            List<String> lines,
            Map<String,String> idToSynonym,
            Map<String,String> teamNames,
            Map<String,PlayerStats> outMap
    ) {
        for (String raw : lines) {
            String[] p = raw.split("\t", -1);
            if ("3".equals(p[0]) && p.length>=7 &&
                (p[2].startsWith("@")||p[2].startsWith("#")) &&
                "Player".equalsIgnoreCase(p[3]))
            {
                String id   = p[2];
                String name = idToSynonym.getOrDefault(id, p[4]);
                String team = teamNames.getOrDefault(p[5], "Unbekannt");
                PlayerStats ps = outMap.computeIfAbsent(id, k -> {
                    PlayerStats x = new PlayerStats();
                    x.id = id; x.name = name; x.team = team;
                    return x;
                });
                teams.computeIfAbsent(team, k->new ArrayList<>()).add(ps);
            }
        }
    }

    private static void processEventLine(String raw) {
        String[] p = raw.split("\t", -1);
        String code = p[2];
        long timestamp = Long.parseLong(p[1]);

        Long lastTs = (Long)STATE.getOrDefault(KEY_TS, -1L);
        String currentOwner = (String)STATE.get(KEY_CUR);

        System.out.println(raw);

        // — Ballbesitz + Pass/Steal/Clear —
        if (Set.of("1100","1103","1107","1109").contains(code)) {
            String next = null;
            switch (code) {
                case "1100": // Pass
                    if (p.length>=6) {
                        String giver = p[3], recv = p[5];
                        Optional.ofNullable(playerMap.get(recv)).ifPresent(ps->ps.passes++);
                        STATE.put(KEY_LAST_PASS, giver);
                        STATE.remove(KEY_LAST_CLEAR);
                        STATE.remove(KEY_LAST_STEAL);
                        STATE.remove(KEY_STEAL_FROM);
                        next = recv;
                    }
                    break;
                case "1103": // Steal
                    if (p.length>=6) {
                        String st = p[3], stFrom = p[5];
                        Optional.ofNullable(playerMap.get(st)).ifPresent(ps->ps.steals++);
                        STATE.put(KEY_LAST_STEAL, st);
                        STATE.put(KEY_STEAL_FROM, stFrom);
                        STATE.remove(KEY_LAST_PASS);
                        STATE.remove(KEY_LAST_CLEAR);
                        next = st;
                    }
                    break;
                case "1107": // Ball erhalten
                    next = p[3];
                    STATE.remove(KEY_LAST_PASS);
                    STATE.remove(KEY_LAST_CLEAR);
                    STATE.remove(KEY_LAST_STEAL);
                    STATE.remove(KEY_STEAL_FROM);
                    break;
                case "1109": // Clear
                    if (p.length>=6) {
                        String clr = p[3], recv = p[5];
                        Optional.ofNullable(playerMap.get(clr)).ifPresent(ps->ps.klaerungen++);
                        STATE.put(KEY_LAST_CLEAR, clr);
                        STATE.remove(KEY_LAST_PASS);
                        STATE.remove(KEY_LAST_STEAL);
                        STATE.remove(KEY_STEAL_FROM);
                        next = recv;
                    }
                    break;
            }
            // alten Besitz abschließen
            if (currentOwner!=null && lastTs>0) {
                Optional.ofNullable(playerMap.get(currentOwner))
                        .ifPresent(ps-> ps.ballbesitzMillis += (timestamp - lastTs));
            }
            // neuen Besitz starten
            if (next!=null) {
                PlayerStats neu = playerMap.get(next);
                if (neu!=null) {
                    neu.lastBallStart = timestamp;
                    STATE.put(KEY_CUR, next);
                    currentBallHolder = next; // Ballhalter aktualisieren
                }
            }
            STATE.put(KEY_TS, timestamp);

            // Live-Update Zeit für Ballbesitzberechnung
            lastBallbesitzUpdate = timestamp;
        }

        // — Tor (1101) mit Vorlagen-Logik —
        if ("1101".equals(code) && p.length>=4) {
            String scorer = p[3];
            PlayerStats sc = playerMap.get(scorer);
            if (sc!=null) {
                sc.goals++;
                int sec = (int)((timestamp - (lastTs>0? lastTs : timestamp))/1000);
                sc.toreZeiten.add((int) timestamp/1000);

                GoalEvent ge = new GoalEvent();
                ge.zeit = sec;
                ge.spielerId   = scorer;
                ge.spielerName = sc.name;
                ge.team         = sc.team;

                if (STATE.containsKey(KEY_LAST_PASS)) {
                    String lp = (String)STATE.get(KEY_LAST_PASS);
                    ge.vorlagenSpielerId   = lp;
                    ge.vorlagenSpielerName = playerMap.get(lp).name;
                    playerMap.get(lp).vorlagen++;
                } else if (STATE.containsKey(KEY_LAST_CLEAR)) {
                    String lc = (String)STATE.get(KEY_LAST_CLEAR);
                    ge.vorlagenSpielerId   = lc;
                    ge.vorlagenSpielerName = playerMap.get(lc).name;
                    playerMap.get(lc).vorlagen++;
                } else if (STATE.containsKey(KEY_LAST_STEAL)) {
                    ge.stealFromSpielerId   = (String)STATE.get(KEY_STEAL_FROM);
                    ge.stealFromSpielerName = playerMap.get(ge.stealFromSpielerId).name;
                }
                goals.add(ge);
            }

            // Besitz beenden
            String owner = (String)STATE.get(KEY_CUR);
            if (owner!=null) {
                Optional.ofNullable(playerMap.get(owner))
                        .ifPresent(ps-> ps.ballbesitzMillis += (timestamp - lastTs));
            }

            // alle Vorlagen-/Steal-Zustände zurücksetzen
            STATE.remove(KEY_CUR);
            STATE.put(KEY_TS, timestamp);
            STATE.remove(KEY_LAST_PASS);
            STATE.remove(KEY_LAST_CLEAR);
            STATE.remove(KEY_LAST_STEAL);
            STATE.remove(KEY_STEAL_FROM);
            currentBallHolder = null; // Ball nach Tor frei
        }

        // — Miss (0201) —
        if ("0201".equals(code) && p.length>=4) {
            playerMap.getOrDefault(p[3], new PlayerStats()).misses++;
        }

        // — Block (1104) —
        if ("1104".equals(code) && p.length>=6) {
            PlayerStats b = playerMap.get(p[3]);
            PlayerStats t = playerMap.get(p[5]);
            if (b!=null && t!=null) {
                b.blocks++;
                t.wurdeGeblockt++;
            }
        }

        // — Spielende (0101) —
        if ("0101".equals(code)) {
            String owner = (String)STATE.get(KEY_CUR);
            if (owner!=null) {
                Optional.ofNullable(playerMap.get(owner))
                        .ifPresent(ps-> ps.ballbesitzMillis += (timestamp - lastTs));
            }
            STATE.remove(KEY_CUR);
            STATE.put(KEY_TS, timestamp);
            STATE.remove(KEY_LAST_PASS);
            STATE.remove(KEY_LAST_CLEAR);
            STATE.remove(KEY_LAST_STEAL);
            STATE.remove(KEY_STEAL_FROM);
            currentBallHolder = null; // Ende → kein Ballhalter
        }

        // Optional: Direkt nach wichtigen Events JSON aktualisieren
        if (code.equals("1101") || Set.of("1100","1103","1107","1109").contains(code) || code.equals("0101")) {
            try {
                JSONObject js = aggregateAndBuildJson(mission, playerMap, teams, goals);
                writeJsonToFile(js);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static JSONObject aggregateAndBuildJson(
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

        JSONObject all = new JSONObject();
        tm.forEach((team, list) -> {
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            int bp=0,t=0,v=0,bl=0,gb=0,mi2=0,kl=0,ps=0,st=0;
            for (PlayerStats p : list) {
                arr.put(p.toJSON());
                bp += p.ballbesitzMillis/1000;
                t  += p.goals;      v += p.vorlagen;
                bl += p.blocks;     gb+= p.wurdeGeblockt;
                mi2+= p.misses;     kl+= p.klaerungen;
                ps += p.passes;     st+= p.steals;
            }
            o.put("spieler", arr);
            o.put("gesamtBallbesitz", bp);
            o.put("tore", t);
            o.put("vorlagen", v);
            o.put("blocks", bl);
            o.put("wurdeGeblockt", gb);
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

        // Neu: aktuellen Ballhalter mit Namen einfügen
        if (currentBallHolder != null && playerMap.containsKey(currentBallHolder)) {
            out.put("aktuellerBallhalter", playerMap.get(currentBallHolder).name);
        } else {
            out.put("aktuellerBallhalter", JSONObject.NULL);
        }

        return out;
    }

    private static String writeJsonToFile(JSONObject result) throws IOException {
        String dir = "C:\\OBSStream";
        File d = new File(dir);
        if (!d.exists()) d.mkdirs();
        File f = new File(d, "Spieldaten.json");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(result.toString(2));
        }
        lastJsonFilePath = f.getAbsolutePath();
        System.out.println("→ JSON geschrieben nach: " + lastJsonFilePath);
        return lastJsonFilePath;
    }

    private static void archiveCurrentJsonFile() {
        if (archived) return;
        if (lastJsonFilePath == null) return;
        File src = new File(lastJsonFilePath);
        if (!src.exists()) return;
        File archDir = new File("C:\\OBSStream\\archive");
        if (!archDir.exists()) archDir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File dst = new File(archDir, "Spieldaten_" + stamp + ".json");
        try {
            Files.copy(src.toPath(), dst.toPath());
            System.out.println("JSON-Datei archiviert: " + dst.getAbsolutePath());
            archived = true;
        } catch (IOException e) {
            System.err.println("Fehler beim Archivieren: " + e.getMessage());
        }
        
        //Nur eine Kopie des letzten spieles im Ordner lassen
        File dir = new File("C:\\OBSStream"); //
        if (!dir.exists()) dir.mkdirs();
        File lastFile = new File(dir, "latestSpieldaten.json");
        try {
            Files.copy(src.toPath(), lastFile.toPath());
            System.out.println("letzte JSON-Datei im Ornder kopiert: " + lastFile.getAbsolutePath());
            archived = true;
        } catch (IOException e) {
            System.err.println("Fehler beim Archivieren: " + e.getMessage());
        }
        
        
    }

    // Hilfsklasse
    private static class MissionInfo {
        boolean foundMission = false;
        String missionId, missionName, startZeit, duration;
        Map<String,String> idToSynonym = new HashMap<>();
        Map<String,String> teamNames  = Map.of("0","Earth","1","Crystal","2","Fire","3","Ice");
    }

}
