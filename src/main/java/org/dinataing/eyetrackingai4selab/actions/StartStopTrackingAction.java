package org.dinataing.eyetrackingai4selab.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.components.ServiceManager;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.DockerManager;
import org.jetbrains.annotations.NotNull;

public class StartStopTrackingAction extends AnAction {

    // The existing actionPerformed method handles the click logic
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject();
        // Use the new way to get a service if your IntelliJ SDK is modern (2020.2+)
        // DockerManager mgr = project.getService(DockerManager.class);
        DockerManager mgr = ServiceManager.getService(DockerManager.class);

        if (mgr == null) return; // Safety check

        if (!mgr.isRunning()) {
            mgr.startOrBuildAndStartAsync(project);
        } else {
            mgr.stopAsync(project);
        }
    }

    // New update method to handle the dynamic text
    @Override
    public void update(@NotNull AnActionEvent e) {
        // Get the Presentation object which controls the appearance
        final Presentation presentation = e.getPresentation();

        // Ensure we have a project before proceeding
        if (e.getProject() == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        // Fetch the DockerManager service
        DockerManager mgr = ServiceManager.getService(DockerManager.class);
        if (mgr == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        // Determine the text based on the DockerManager's state
        if (mgr.isRunning()) {
            // If the manager is running, the next action is to STOP it
            presentation.setText("Stop Tracking");
            presentation.setDescription("Stop gaze tracking");
             presentation.setIcon(AllIcons.Actions.Suspend);
        } else {
            // If the manager is not running, the next action is to START it
            presentation.setText("Start Tracking");
            presentation.setDescription("Start gaze tracking");
            presentation.setIcon(AllIcons.Actions.Execute);
        }

        presentation.setEnabledAndVisible(true);
    }
}