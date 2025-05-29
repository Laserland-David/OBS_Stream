package main;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Live-TDF-Parser:
 * - Puffer bis Code "9" → Initialisierung (MissionInfo + Players)
 * - Dann alle 4-Zeilen live verarbeiten
 * - Jede Sekunde JSON aktualisieren
 */
public class Main {

    // === globaler Zustand ===
    private static volatile boolean initialized = false;
    private static final List<String> prebuffer = Collections.synchronizedList(new ArrayList<>());
    private static MissionInfo mission;
    private static final Map<String, PlayerStats> playerMap = new HashMap<>();
    private static final Map<String, List<PlayerStats>> teams = new HashMap<>();
    private static final List<GoalEvent> goals = new ArrayList<>(); 

    /*
    public static void main(String[] args) throws Exception {
        // 1-Sekunden-Scheduler zum fortlaufenden JSON-Schreiben
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        sched.scheduleAtFixedRate(() -> {
        	System.out.println(initialized);
            if (!initialized) return;
            try {
                // 1) JSON erzeugen
                JSONObject js = aggregateAndBuildJson(mission, playerMap, teams, goals);
                // 2) in Datei schreiben (gibt den Dateinamen zurück)
                String outFile = writeJsonToFile(js);
                // 3) an dein Plugin pushen
                WriteToPlugin.writeToPlugin(outFile);
                System.out.println("→ JSON gepusht: " + outFile);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 1, 1, TimeUnit.SECONDS);

        // TCP-Listener auf Port 7001
        listenTcpAndProcess(7001);
    }*/
    
    public static void main(String[] args) throws Exception {
        // 1) Thread für das Live-Schreiben der JSON jede Sekunde
        Thread writerThread = new Thread(() -> {
            try {
                // Warte, bis initialisiert
                while (!initialized) {
                    Thread.sleep(100);
                }
                // Schreib-Schleife
                while (true) {
                    JSONObject js = aggregateAndBuildJson(mission, playerMap, teams, goals);
                    String outFile = writeJsonToFile(js);
           //         WriteToPlugin.writeToPlugin(outFile);
           //         System.out.println("→ JSON gepusht: " + outFile);
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ie) {
                // Thread unterbrochen: sauber beenden
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "JSON-Writer");
        writerThread.setDaemon(true);
        writerThread.start();

        // 2) Haupt-Thread: TCP-Listener
        listenTcpAndProcess(7001);
    }


    /** Starte einen TCP-Server, der jede Zeile an handleLine() gibt */
    public static void listenTcpAndProcess(int port) {
        System.out.println("Starte TCP Server auf Port " + port + " und warte auf Verbindungen...");
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            Socket client = serverSocket.accept();
            System.out.println("Verbindung von: " + client.getInetAddress());
            try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    handleLine(line.trim());
                }
            }
            System.out.println("Verbindung beendet.");
        } catch (IOException e) {
            System.err.println("Fehler im TCP Server: " + e.getMessage());
        }
    }

    /** Puffer + Erst-Init bei erstem Code "9" + live-Verarbeitung */
    private static void handleLine(String raw) {
        if (!initialized) prebuffer.add(raw);
        String[] p = raw.split("\t", -1);

        // 1st trigger: Code "9" zur Initialisierung
        if (!initialized && p.length>0 && "9".equals(p[0])) {
            mission = loadMissionInfoAndSynonymsFromLines(prebuffer);
            registerPlayersFromLines(prebuffer, mission.idToSynonym, mission.teamNames, playerMap);
            // alle gepufferten 4-Zeilen einmal nachholen
            prebuffer.stream().filter(l->l.startsWith("4\t")).forEach(Main::processEventLine);
            prebuffer.clear();
            initialized = true;
            System.out.println(">>> INITIALISIERT nach Code 9");
        }

        // nach Init: jede 4-Zeile live parsen
        if (initialized && raw.startsWith("4\t")) {
            processEventLine(raw);
        }
    }

    // === Init-Hilfen aus prebuffer ===

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
            if ("3".equals(p[0]) && p.length>=7 && (p[2].startsWith("@")||p[2].startsWith("#"))
                    && "Player".equalsIgnoreCase(p[3])) {
                String id   = p[2];
                String name = idToSynonym.getOrDefault(id, p[4]);
                String team = teamNames.getOrDefault(p[5], "Unbekannt");
                PlayerStats ps = outMap.computeIfAbsent(id, k->{
                    PlayerStats x = new PlayerStats();
                    x.id = id; x.name = name; x.team = team;
                    return x;
                });
                teams.computeIfAbsent(team, k->new ArrayList<>()).add(ps);
            }
        }
    }

    // === DER BISHERIGE EVENT-PARSER jetzt in einer Methode ===

    private static void processEventLine(String raw) {
        String[] p = raw.split("\t", -1);
        String code = p[2];
        long timestamp = Long.parseLong(p[1]);
        // statische Variablen für Ballbesitz
        // wir packen sie als ThreadLocal hier, für Kürze als Felder:
        // (in echt würde man das auslagern)
        // siehe unten für Erklärung
        // ---------------------------------------------
        // lokaler State in einer Map:
        String KEY_CUR = "__cur__";      // current owner
        String KEY_TS  = "__lastTs__";   // last timestamp
        ThreadLocal<Map<String,Object>> S = ThreadLocal.withInitial(HashMap::new);
        Map<String,Object> M = S.get();
        // ---------------------------------------------
        Long lastTs = (Long)M.getOrDefault(KEY_TS, -1L);
        String currentOwner = (String)M.get(KEY_CUR);
        System.out.println(raw);
        // Ballbesitz-Codes
        if (Set.of("1100","1103","1107","1109").contains(code)) {
            String next = null;
            switch (code) {
                case "1100": // Pass
                    if (p.length>=6) {
                        String giver = p[3], recv = p[5];
                        playerMap.getOrDefault(recv, new PlayerStats()).passes++;
                        M.put("lastPass", giver);
                        M.remove("lastClear"); M.remove("lastSteal"); M.remove("lastStealFrom");
                        next = recv;
                    }
                    break;
                case "1103": // Steal
                    if (p.length>=6) {
                        String st = p[3], stFrom = p[5];
                        playerMap.getOrDefault(st, new PlayerStats()).steals++;
                        M.put("lastSteal", st); M.put("lastStealFrom", stFrom);
                        M.remove("lastPass"); M.remove("lastClear");
                        next = st;
                    }
                    break;
                case "1107": // Ball erhalten
                    next = p[3];
                    M.remove("lastPass"); M.remove("lastClear"); M.remove("lastSteal"); M.remove("lastStealFrom");
                    break;
                case "1109": // Clear
                    if (p.length>=6) {
                        String clr = p[3], recv = p[5];
                        playerMap.getOrDefault(clr, new PlayerStats()).klaerungen++;
                        M.put("lastClear", clr);
                        M.remove("lastPass"); M.remove("lastSteal"); M.remove("lastStealFrom");
                        next = recv;
                    }
                    break;
            }
            // beende alten Besitz
            if (currentOwner!=null && lastTs>0) {
                PlayerStats old = playerMap.get(currentOwner);
                old.ballbesitzMillis += (timestamp - ((Long)M.get(KEY_TS)));
            }
            // start neuer Besitz
            if (next != null) {
                PlayerStats neu = playerMap.get(next);
                if (neu != null) {
                    neu.lastBallStart = timestamp;
                    M.put(KEY_CUR, next);
                } else {
                    System.err.println("Warnung: Spieler mit ID " + next + " nicht gefunden für Ballbesitz-Event.");
                }
            }

            M.put(KEY_TS, timestamp);
        }

        // Tor (1101)
        if ("1101".equals(code) && p.length>=4) {
            String scorer = p[3];
            PlayerStats sc = playerMap.get(scorer);
            sc.goals++;
            int sec = (int)((timestamp - ((Long)prebuffer.stream()
                    .filter(l->l.startsWith("4\t"))
                    .findFirst().map(l->Long.parseLong(l.split("\t",2)[1]))
                    .orElse(timestamp))) / 1000);
            sc.toreZeiten.add(sec);

            GoalEvent ge = new GoalEvent();
            ge.zeit = sec;
            ge.spielerId = scorer;
            ge.spielerName = sc.name;
            ge.team = sc.team;

            if (M.containsKey("lastPass")) {
                String lp = (String)M.get("lastPass");
                ge.vorlagenSpielerId = lp;
                ge.vorlagenSpielerName = playerMap.get(lp).name;
                playerMap.get(lp).vorlagen++;
            } else if (M.containsKey("lastClear")) {
                String lc = (String)M.get("lastClear");
                ge.vorlagenSpielerId = lc;
                ge.vorlagenSpielerName = playerMap.get(lc).name;
                playerMap.get(lc).vorlagen++;
            } else if (M.containsKey("lastSteal")) {
                ge.stealFromSpielerId   = (String)M.get("lastStealFrom");
                ge.stealFromSpielerName = playerMap.get(ge.stealFromSpielerId).name;
            }
            goals.add(ge);

            // beende Besitz
            if (M.get(KEY_CUR)!=null) {
                PlayerStats old = playerMap.get(M.get(KEY_CUR));
                old.ballbesitzMillis += timestamp - ((Long)M.get(KEY_TS));
            }
            M.remove(KEY_CUR); M.put(KEY_TS, timestamp);
            M.remove("lastPass"); M.remove("lastClear"); M.remove("lastSteal"); M.remove("lastStealFrom");
        }

        // Miss (0201)
        if ("0201".equals(code) && p.length >= 4) {
            PlayerStats ps = playerMap.get(p[3]);
            if (ps != null) {
                ps.misses++;
            } else {
                System.err.println("Warnung: Spieler mit ID " + p[3] + " nicht gefunden für Misses-Event.");
            }
        }

        // Block (1104)
        if ("1104".equals(code) && p.length >= 6) {
            PlayerStats blocker = playerMap.get(p[3]);
            PlayerStats geblockt = playerMap.get(p[5]);
            if (blocker != null && geblockt != null) {
                blocker.blocks++;
                geblockt.wurdeGeblockt++;
            } else {
                System.err.println("Warnung: Spieler für Block-Event nicht gefunden: " + p[3] + " oder " + p[5]);
            }
        }

        // Spielende (0101)
        if ("0101".equals(code)) {
            if (M.get(KEY_CUR)!=null) {
                PlayerStats old = playerMap.get(M.get(KEY_CUR));
                old.ballbesitzMillis += timestamp - ((Long)M.get(KEY_TS));
            }
            M.remove(KEY_CUR); M.put(KEY_TS, timestamp);
            M.remove("lastPass"); M.remove("lastClear"); M.remove("lastSteal"); M.remove("lastStealFrom");
        }
    }

    // === JSON-Aggregate & Schreiben ===

    private static JSONObject aggregateAndBuildJson(
            MissionInfo mi,
            Map<String,PlayerStats> playerMap,
            Map<String,List<PlayerStats>> teams,
            List<GoalEvent> goals
    ) {
        JSONObject out = new JSONObject();
        //out.put("spielId", mi.missionId + "_" + mi.startZeit); // zum speochern für mehr
        out.put("spielId", "Spieldaten");
        out.put("missionId", mi.missionId);
        out.put("missionName", mi.missionName);
        out.put("startZeit", mi.startZeit);
        out.put("duration", mi.duration);

        JSONObject allTeams = new JSONObject();
        teams.forEach((teamName, list) -> {
            JSONObject to = new JSONObject();
            JSONArray pa = new JSONArray();
            int bp = 0, t = 0, v = 0, bl = 0, gb = 0, mi2 = 0, kl = 0, ps = 0, st = 0;
            for (PlayerStats psr : list) {
                pa.put(psr.toJSON());
                bp += psr.ballbesitzMillis / 1000;
                t += psr.goals;
                v += psr.vorlagen;
                bl += psr.blocks;
                gb += psr.wurdeGeblockt;
                mi2 += psr.misses;
                kl += psr.klaerungen;
                ps += psr.passes;
                st += psr.steals;
            }
            to.put("spieler", pa);
            to.put("gesamtBallbesitz", bp);
            to.put("tore", t);
            to.put("vorlagen", v);
            to.put("blocks", bl);
            to.put("wurdeGeblockt", gb);
            to.put("misses", mi2);
            to.put("klaerungen", kl);
            to.put("passe", ps);
            to.put("steals", st);
            allTeams.put(teamName, to);
        });
        out.put("teams", allTeams);

        JSONArray ga = new JSONArray();
        goals.forEach(g -> ga.put(g.toJSON()));
        out.put("tore", ga);

        return out;
    }

    private static String writeJsonToFile(JSONObject result) throws IOException {
        // Ziel-Ordner unter Windows
        String dirPath = "C:\\OBSStream";
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs(); // falls noch nicht vorhanden, anlegen
        }

        // Dateiname im Zielordner
        File f = new File(dir, "Spieldaten.json");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(result.toString(2));
        }
        System.out.println("→ JSON geschrieben nach: " + f.getAbsolutePath());
        return f.getAbsolutePath();
    }


    // === Hilfsklasse ===
    private static class MissionInfo {
        boolean foundMission = false;
        String missionId, missionName, startZeit, duration;
        Map<String,String> idToSynonym = new HashMap<>();
        Map<String,String> teamNames  = Map.of("0","Earth","1","Crystal","2","Fire","3","Ice");
    }
}