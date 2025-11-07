package org.dinataing.eyetrackingai4selab.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;

public class PauseResumeTrackingAction extends AnAction {
    private boolean paused = false;

    @Override
    public void actionPerformed(AnActionEvent e) {
        paused = !paused;
        String msg = paused ? "Tracking paused." : "Tracking resumed.";
        Messages.showInfoMessage(msg, "AI4SE Eye Tracking");
    }
}
