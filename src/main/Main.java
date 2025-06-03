package main;

import org.json.JSONObject;

public class Main {

    private static final GameState state = new GameState();

    public static void main(String[] args) throws Exception {
        // Writer-Thread, der alle 1 Sekunde Ballbesitz neu berechnet und JSON schreibt
        Thread writer = new Thread(() -> {
            try {
                // Warten bis initialisiert
                while (!state.initialized) Thread.sleep(100);

                while (true) {
                    state.recalculateBallPossession();

                    boolean currentActive = state.gameActive;

                    // Schreibe JSON nur, wenn Spiel aktiv ist oder gerade eben aufgehört hat (Übergang)
                    if (currentActive || (!currentActive && state.lastGameActiveState)) {
                        JSONObject js = state.aggregateAndBuildJson(state.mission,
                                state.playerMap,
                                state.teams,
                                state.goals);

                        FileUtils.writeJsonToFile(state, js);
                    }

                    // Merke aktuellen Spielstatus für nächsten Durchlauf
                    state.lastGameActiveState = currentActive;

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

        // TCP-Server starten und State übergeben
        TcpServer server = new TcpServer(state);
        server.listenTcpAndProcess(7001);
    }
}
