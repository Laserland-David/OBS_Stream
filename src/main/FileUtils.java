package main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.json.JSONObject;

public class FileUtils {

	    static void archiveCurrentJsonFile(GameState state) {
	        if (state.archived) return;
	        if (state.lastJsonFilePath == null) return;
	        File src = new File(state.lastJsonFilePath);
	        if (!src.exists()) return;
	        File archDir = new File("C:\\OBSStream\\archive");
	        if (!archDir.exists()) archDir.mkdirs();
	        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
	        File dst = new File(archDir, "Spieldaten_" + stamp + ".json");
	        try {
	            Files.copy(src.toPath(), dst.toPath());
	            System.out.println("JSON-Datei archiviert: " + dst.getAbsolutePath());
	            state.archived = true;
	        } catch (IOException e) {
	            System.err.println("Fehler beim Archivieren: " + e.getMessage());
	        }
	        
	    }

		 static void copyAndReplaceLatestSpieldaten(GameState state) {
			  if (state.lastJsonFilePath == null) return;
			//Nur eine Kopie des letzten spieles im Ordner lassen
	        File dir = new File("C:\\OBSStream"); //
	        if (!dir.exists()) dir.mkdirs();
	        File lastFile = new File(dir, "latestSpieldaten.json");
	        try {
	            Files.copy(new File(state.lastJsonFilePath).toPath(), lastFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
	            System.out.println("letzte JSON-Datei im Ornder kopiert: " + lastFile.getAbsolutePath());
	            state.archived = true;
	        } catch (IOException e) {
	            System.err.println("Fehler beim Archivieren: " + e.getMessage());
	        }
		}
	    
	     static String writeJsonToFile(GameState state,JSONObject result) throws IOException {
	        String dir = "C:\\OBSStream";
	        File d = new File(dir);
	        if (!d.exists()) d.mkdirs();
	        File f = new File(d, "Spieldaten.json");
	        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
	            w.write(result.toString(2));
	        }
	        state.lastJsonFilePath = f.getAbsolutePath();
	        System.out.println("→ JSON geschrieben nach: " + state.lastJsonFilePath);
	        return state.lastJsonFilePath;
	    }
}
