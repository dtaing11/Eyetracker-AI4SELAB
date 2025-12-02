package org.dinataing.eyetrackingai4selab.eyetracking.runtime.eyetracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze.EditorGazeMapper;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze.GazeHit;
import org.dinataing.eyetrackingai4selab.utils.XMLWriter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;


import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

public class EyeTracker {

    private final Document eyeTrackingDoc;
    private final Element root;
    private final Element setting;
    private final Element gazes;

    private boolean isTracking = false;
    private boolean isRealTimeDataTransmitting = false;
    private Consumer<Element> gazeHandler;

    private String projectPath = "";
    private String filePath = "";
    private String dataOutputPath = "";

    public EyeTracker() throws ParserConfigurationException {
        eyeTrackingDoc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .newDocument();

        root = eyeTrackingDoc.createElement("eye_tracking");
        setting = eyeTrackingDoc.createElement("setting");
        gazes = eyeTrackingDoc.createElement("gazes");

        eyeTrackingDoc.appendChild(root);
        root.appendChild(setting);
        root.appendChild(gazes);
    }



    public void start(Project project, String projectPath, String filePath, String dataOutputPath) {
        this.isTracking = true;
        this.projectPath = projectPath;
        this.filePath = filePath;
        this.dataOutputPath = dataOutputPath;

        // 🔹 Ensure output directory exists
        if (dataOutputPath != null && !dataOutputPath.isEmpty()) {
            try {
                Path dir = Paths.get(dataOutputPath);
                Files.createDirectories(dir); // creates parent dirs if needed
                System.out.println("[AI4SE] Ensured output dir exists: " + dir);
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("[AI4SE] Failed to create data output directory: " + dataOutputPath);
            }
        }

        setting.setAttribute("project_path", projectPath);
        setting.setAttribute("file_path", filePath);
        setting.setAttribute("ide", "IntelliJ");
        setting.setAttribute("tracker", "AI4SE-EyeTracker");
    }

    public void stop() throws TransformerException {
        this.isTracking = false;
        if (dataOutputPath != null && !dataOutputPath.isEmpty()) {
            try {
                Path dir = Paths.get(dataOutputPath);
                Files.createDirectories(dir);
            } catch (Exception e) {
                e.printStackTrace();
            }

            String out = dataOutputPath + "/eye_tracking.xml";
            XMLWriter.writeToXML(eyeTrackingDoc, out);
            System.out.println("[AI4SE] Eye tracking XML written to: " + out);
        }
    }


    public void setRealTime(boolean realTime) {
        this.isRealTimeDataTransmitting = realTime;
    }

    public void setGazeHandler(Consumer<Element> handler) {
        this.gazeHandler = handler;
    }

    /**
     * Process one JSON line from Python.
     * Example:
     * {
     *   "type": "gaze",
     *   "timestamp": 1764655830347,
     *   "leftX": 0.19, "leftY": 0.13, ...
     * }
     */
    public void processRawJson(Project project, String jsonLine) {
        if (!isTracking) return;

        JsonObject obj = JsonParser.parseString(jsonLine).getAsJsonObject();
        if (!"gaze".equals(obj.get("type").getAsString())) {
            return;
        }

        long timestamp = obj.get("timestamp").getAsLong();
        double leftX = obj.get("leftX").getAsDouble();
        double leftY = obj.get("leftY").getAsDouble();
        double rightX = obj.get("rightX").getAsDouble();
        double rightY = obj.get("rightY").getAsDouble();

        // avg gaze
        double gx = (leftX + rightX) / 2.0;
        double gy = (leftY + rightY) / 2.0;

        // --- XML: raw gaze node ---
        Element gaze = eyeTrackingDoc.createElement("gaze");
        gazes.appendChild(gaze);

        gaze.setAttribute("timestamp", String.valueOf(timestamp));
        gaze.setAttribute("leftX", String.valueOf(leftX));
        gaze.setAttribute("leftY", String.valueOf(leftY));
        gaze.setAttribute("rightX", String.valueOf(rightX));
        gaze.setAttribute("rightY", String.valueOf(rightY));
        gaze.setAttribute("gx", String.valueOf(gx));
        gaze.setAttribute("gy", String.valueOf(gy));

        // --- map to editor ---
        GazeHit hit = EditorGazeMapper.mapGazeToEditor(project, gx, gy);
        if (hit == null) {
            gaze.setAttribute("remark", "Fail | Mapping");
            handleElement(gaze);
            return;
        }

        // --- location sub-element ---
        Element location = eyeTrackingDoc.createElement("location");
        location.setAttribute("screen_x", String.valueOf(hit.screenPoint.x));
        location.setAttribute("screen_y", String.valueOf(hit.screenPoint.y));
        location.setAttribute("editor_x", String.valueOf(hit.editorTopLeft.x));
        location.setAttribute("editor_y", String.valueOf(hit.editorTopLeft.y));
        location.setAttribute("local_x", String.valueOf(hit.localPoint.x));
        location.setAttribute("local_y", String.valueOf(hit.localPoint.y));
        location.setAttribute("line", String.valueOf(hit.logicalPosition.line));
        location.setAttribute("column", String.valueOf(hit.logicalPosition.column));
        location.setAttribute("offset", String.valueOf(hit.offset));
        location.setAttribute("char", String.valueOf(hit.ch));
        location.setAttribute("word", hit.word != null ? hit.word : "");
        location.setAttribute("path", relativizePath(filePath, projectPath));

        gaze.appendChild(location);

        // --- AST structure ---
        Element ast = buildAstStructure(hit);
        gaze.appendChild(ast);

        handleElement(gaze);
    }

    private void handleElement(Element element) {
        if (gazeHandler != null && isRealTimeDataTransmitting) {
            gazeHandler.accept(element);
        }
    }

    /**
     * Build AST structure like the reference EyeTracker.
     */
    private Element buildAstStructure(GazeHit hit) {
        Element ast = eyeTrackingDoc.createElement("ast_structure");

        PsiElement psi = hit.psiElement;
        if (psi == null) {
            ast.setAttribute("token", "");
            ast.setAttribute("type", "");
            ast.setAttribute("remark", "No PSI element");
            return ast;
        }

        String token = psi.getText();
        String type = psi.getNode().getElementType().toString();
        ast.setAttribute("token", token);
        ast.setAttribute("type", type);

        PsiElement parent = psi;
        int level = 0;
        while (parent != null) {
            if (parent instanceof PsiFile) break;

            Element levelElem = eyeTrackingDoc.createElement("level");
            levelElem.setAttribute("tag", String.valueOf(parent));

            int startOffset = parent.getTextRange().getStartOffset();
            int endOffset = parent.getTextRange().getEndOffset();

            LogicalPosition startPos = hit.logicalPosition; // default
            LogicalPosition endPos = hit.logicalPosition;   // default

            try {
                // runReadAction because we touch PSI
                startPos = ApplicationManager.getApplication().runReadAction(
                        (com.intellij.openapi.util.Computable<LogicalPosition>) () ->
                                hit.psiElement.getContainingFile()
                                        .getViewProvider().getDocument() != null
                                        ? hit.psiElement.getContainingFile().getViewProvider()
                                        .getDocument()
                                        .getLineNumber(startOffset) >= 0
                                        ? hit.logicalPosition
                                        : hit.logicalPosition
                                        : hit.logicalPosition
                );
            } catch (Exception ignored) {
                // To keep it simple; you can improve this with real offset->LogicalPosition mapping
            }

            levelElem.setAttribute("start", startOffset + "");
            levelElem.setAttribute("end", endOffset + "");
            ast.appendChild(levelElem);

            parent = parent.getParent();
            level++;
        }

        return ast;
    }

    private static String relativizePath(String filePath, String projectPath) {
        if (filePath == null || projectPath == null) return filePath;
        if (filePath.startsWith(projectPath)) {
            return filePath.substring(projectPath.length());
        }
        return filePath;
    }
}
