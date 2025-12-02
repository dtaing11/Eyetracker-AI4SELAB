package org.dinataing.eyetrackingai4selab.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.DockerManager;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.eyetracker.EyeTracker;
import org.jetbrains.annotations.NotNull;

public class StartStopTrackingAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject();
        if (project == null) return;

        DockerManager mgr = ServiceManager.getService(DockerManager.class);
        if (mgr == null) return;

        if (!mgr.isRunning()) {
            try {
                String projectPath = project.getBasePath();
                if (projectPath == null) {
                    System.out.println("[AI4SE] Project base path is null");
                    return;
                }

                // Data directory: <project>/.ai4se-data
                java.nio.file.Path dataDir = java.nio.file.Paths.get(projectPath, ".ai4se-data");
                java.nio.file.Files.createDirectories(dataDir);

                // Current file if any
                var editor = com.intellij.openapi.fileEditor.FileEditorManager
                        .getInstance(project)
                        .getSelectedTextEditor();
                String filePath = "";
                if (editor != null) {
                    var vFile = com.intellij.openapi.fileEditor.FileDocumentManager
                            .getInstance()
                            .getFile(editor.getDocument());
                    if (vFile != null) {
                        filePath = vFile.getPath();
                    }
                }

                // Create + start XML eye tracker
                EyeTracker eyeTracker = new EyeTracker();
                eyeTracker.start(project, projectPath, filePath, dataDir.toString());
                eyeTracker.setRealTime(true);
                eyeTracker.setGazeHandler(element -> {
                    String ts = element.getAttribute("timestamp");
                    String word = element
                            .getElementsByTagName("location")
                            .item(0)
                            .getAttributes()
                            .getNamedItem("word")
                            .getNodeValue();
                    System.out.println("[AI4SE][RT] t=" + ts + " word=" + word);
                });

                mgr.attachEyeTracker(eyeTracker);

                // Start Docker tracker
                mgr.startOrBuildAndStartAsync(project);

            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } else {
            mgr.stopAsync(project);
        }
    }


    @Override
    public void update(@NotNull AnActionEvent e) {
        final Presentation presentation = e.getPresentation();
        Project project = e.getProject();

        if (project == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        DockerManager mgr = project.getService(DockerManager.class);
        if (mgr == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        if (mgr.isRunning()) {
            presentation.setText("Stop Tracking");
            presentation.setDescription("Stop gaze tracking");
            presentation.setIcon(AllIcons.Actions.Suspend);
        } else {
            presentation.setText("Start Tracking");
            presentation.setDescription("Start gaze tracking");
            presentation.setIcon(AllIcons.Actions.Execute);
        }

        presentation.setEnabledAndVisible(true);
    }
}
