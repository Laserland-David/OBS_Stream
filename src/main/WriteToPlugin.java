package main;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class WriteToPlugin {
    public static void writeToPlugin(String outputFile) {
        String apiUrl = "https://lasertagbautzen.de/wp-json/ballbesitz/v1/save";
        try {
            StringBuilder json = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(outputFile))) {
                String l;
                while ((l = r.readLine()) != null) json.append(l);
            }
            var url = new java.net.URL(apiUrl);
            var conn = (java.net.HttpURLConnection)url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type","application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("Plugin-Response: " + conn.getResponseCode());
        } catch (Exception e) {
            System.err.println("Fehler beim Push: "+e.getMessage());
        }
    }
}