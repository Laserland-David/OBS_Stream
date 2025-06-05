package main;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class TcpServer {
    private final GameState state;

    public TcpServer(GameState state) {
        this.state = state;
    }

    public void listenTcpAndProcess(int port) {
        System.out.println("Starte TCP Server auf Port " + port + "...");
        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                state.archived = false;
                try (Socket sock = ss.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)))
                {
                    //Letzte SPieldaten erst zu beginn einer neuen Runde ersetzen
                	FileUtils.copyAndReplaceLatestSpieldaten(state);
                	
                	System.out.println("Verbindung von " + sock.getInetAddress());
                    String line;
                    while ((line = in.readLine()) != null) {
                        state.handleLine(line.trim()); 
                    }
                    System.out.println("Verbindung beendet.");
                    if (state.gameActive) {
                        // Wenn Verbindung zu Ende, aber Spiel noch aktiv → Abbruch
                        state.rundeAbgebrochen = true;
                        state.gameActive = false;
                    }
                    //FileUtils.archiveCurrentJsonFile(state);
                    //state.resetForNextSession();
                } catch (IOException e) {
                    System.err.println("Fehler bei Verbindung: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server-Fehler: " + e.getMessage());
        }
    }
}