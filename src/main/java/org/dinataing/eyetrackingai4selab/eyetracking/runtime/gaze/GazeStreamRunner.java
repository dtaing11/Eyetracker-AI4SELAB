package org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze;

import com.intellij.openapi.project.Project;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.eyetracker.EyeTracker;
import org.w3c.dom.Element;

import javax.xml.transform.TransformerException;

public class GazeStreamRunner {

    // This is your XML/AST EyeTracker layer in this package
    private final EyeTracker eyeTracker;

    public GazeStreamRunner(Project project,
                            String projectPath,
                            String filePath,
                            String dataOutputPath) throws Exception {

        this.eyeTracker = new EyeTracker();
        eyeTracker.start(project, projectPath, filePath, dataOutputPath);

        // Optional: real-time consumer (e.g., send to socket or log)
        eyeTracker.setRealTime(true);
        eyeTracker.setGazeHandler(gazeElement -> {
            // Example: just log the word + token
            String ts = gazeElement.getAttribute("timestamp");

            Element location =
                    (Element) gazeElement.getElementsByTagName("location").item(0);
            Element ast =
                    (Element) gazeElement.getElementsByTagName("ast_structure").item(0);

            if (location != null && ast != null) {
                String word = location.getAttribute("word");
                String token = ast.getAttribute("token");
                String type = ast.getAttribute("type");

                System.out.println(
                        "[AI4SE][RT] t=" + ts +
                                " word=" + word +
                                " token=" + token +
                                " type=" + type
                );
            }
        });
    }

    public void processLine(Project project, String line) {
        // Strip your log prefix if needed:
        // e.g. "[AI4SE Python RAW] " then JSON
        String json = line.trim();
        int idx = json.indexOf('{');
        if (idx > 0) {
            json = json.substring(idx);
        }
        eyeTracker.processRawJson(project, json);
    }

    public void stop() throws TransformerException {
        eyeTracker.stop();
    }
}
