package org.dinataing.eyetrackingai4selab.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.DockerManager;

public class StartStopTrackingAction extends AnAction {
    @Override public void actionPerformed(AnActionEvent e) {
        var project = e.getProject();
        DockerManager mgr = ServiceManager.getService(DockerManager.class);
        if (!mgr.isRunning()) {
            mgr.startOrBuildAndStartAsync(project);
        } else {
            mgr.stopAsync(project);
        }
    }
}