package org.dinataing.eyetrackingai4selab.eyetracking.runtime;

import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.eyetracker.EyeTracker;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze.EditorGazeMapper;
import org.dinataing.eyetrackingai4selab.eyetracking.runtime.gaze.JsonEyeTrackerMapper;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import javax.swing.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public final class DockerManager implements Disposable {
    private static final Logger LOG = Logger.getInstance(DockerManager.class);

    private static final String IMAGE_BASE = "ai4se/eyetracking";
    private static final String CONTAINER_NAME = "ai4se-tracker";
    private static final int CONTAINER_PORT = 5000; // inside the container
    private EyeTracker eyeTracker;
    private Process runProcess;
    private OSProcessHandler runHandler;
    private String imageTag;
    private int hostPort = -1; // chosen dynamically at start

    private Project project;   // needed for editor mapping

    // -------------------- Public helpers (EDT-safe) --------------------

    /** Non-blocking start/build; safe to call from actions (EDT). */
    public void startOrBuildAndStartAsync(Project project) {
        this.project = project;
        ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "AI4SE: Starting Tracker", false) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        try {
                            startOrBuildAndStart(); // runs off-EDT here
                        } catch (Exception ex) {
                            LOG.warn("AI4SE start failed", ex);
                            System.err.println("[AI4SE] Start failed: " + ex.getMessage());
                        }
                    }
                }
        );
    }
    public void attachEyeTracker(EyeTracker eyeTracker) {
        this.eyeTracker = eyeTracker;
    }

    /** Non-blocking stop; safe to call from actions (EDT). */
    public void stopAsync(Project project) {
        ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "AI4SE: Stopping Tracker", false) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        stop(); // runs off-EDT here
                    }
                }
        );
    }

    public synchronized boolean isRunning() {
        return runProcess != null && runProcess.isAlive();
    }

    /** The chosen host port mapped to container port 5000 (valid after start). */
    public int getHostPort() { return hostPort; }

    // -------------------- Core (blocking) logic; call off-EDT --------------------

    public synchronized void startOrBuildAndStart() throws Exception {
        ensureDockerInstalled();

        // Stage the embedded Docker context from resources
        Path ctx = ResourceExtractor.stageDockerContext(DockerManager.class);

        // IMPORTANT: include the actual staged file names in the content hash.
        String contentHash = ResourceExtractor
                .sha256(ctx, List.of("Dockerfile", "requirements.txt", "eyetracker.py"))
                .substring(0, 12);
        imageTag = IMAGE_BASE + ":" + contentHash;

        // Build image if missing
        if (!imageExists(imageTag)) {
            LOG.info("[AI4SE] building image " + imageTag);
            System.out.println("[AI4SE] Building Docker image: " + imageTag);
            runAndCheckWithLogs(
                    new ProcessBuilder("docker", "build", "-t", imageTag, ctx.toString()),
                    "[AI4SE Build] "
            );
        } else {
            LOG.info("[AI4SE] image already present: " + imageTag);
            System.out.println("[AI4SE] Image already present: " + imageTag);
        }

        if (isRunning()) {
            LOG.info("[AI4SE] container already running.");
            System.out.println("[AI4SE] Container already running.");
            return;
        }

        // Best-effort cleanup of any stale container
        try {
            new ProcessBuilder("docker", "rm", "-f", CONTAINER_NAME)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        // Decide how to run Docker based on OS (Linux vs others)
        String os = System.getProperty("os.name").toLowerCase();
        List<String> cmd;

        if (os.contains("linux")) {
            // On Linux: host networking helps Tobii discovery
            cmd = Arrays.asList(
                    "docker", "run", "--rm",
                    "--name", CONTAINER_NAME,
                    "--network=host",
                    imageTag
            );
            hostPort = CONTAINER_PORT;
            System.out.println("[AI4SE] Starting tracker with --network=host on Linux");
        } else {
            hostPort = findFreePort();
            cmd = Arrays.asList(
                    "docker", "run", "--rm",
                    "--name", CONTAINER_NAME,
                    "-p", hostPort + ":" + CONTAINER_PORT,
                    imageTag
            );
            System.out.println("[AI4SE] Starting tracker with -p " + hostPort + ":" + CONTAINER_PORT);
        }

        String commandLine = String.join(" ", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        runProcess = pb.start();

        System.out.println("[AI4SE] Container starting with: " + commandLine);
        System.out.println("[AI4SE] Tracker mapped to http://localhost:" + hostPort);

        // Capture logs and JSON messages safely
        startWithHandler(runProcess, commandLine, "[AI4SE Docker] ");
    }

    public synchronized void stop() {
        stopHandlerIfAny();

        if (isRunning()) {
            runProcess.destroy();
            try {
                runProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            runProcess = null;
        }
        try {
            new ProcessBuilder("docker", "stop", CONTAINER_NAME).start();
        } catch (IOException ignored) {}
        hostPort = -1;
        System.out.println("[AI4SE] Tracker stopped.");

        // 👇 NEW: flush XML and cleanup eye tracker
        if (eyeTracker != null) {
            try {
                eyeTracker.stop();
            } catch (Exception e) {
                LOG.warn("[AI4SE] Failed to stop EyeTracker", e);
            }
        }
    }

    // -------------------- Process/log wiring --------------------

    /** Run a short-lived command (like build) with logs and error propagation. */
    private void runAndCheckWithLogs(ProcessBuilder pb, String prefix) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process p = pb.start();

        String commandLine = String.join(" ", pb.command());
        OSProcessHandler handler = new OSProcessHandler(p, commandLine);
        handler.addProcessListener(new ProcessAdapter() {
            @Override
            public void onTextAvailable(ProcessEvent event, Key outputType) {
                String t = event.getText();
                if (t != null) {
                    String line = t.trim();
                    LOG.info(prefix + line);
                    System.out.println(prefix + line);
                }
            }
        });
        handler.startNotify();

        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("Command failed (" + exit + "): " + commandLine);
        }
    }

    private void startWithHandler(Process process, String commandLine, String prefix) {
        stopHandlerIfAny(); // safety
        runHandler = new OSProcessHandler(process, commandLine);

        runHandler.addProcessListener(new ProcessAdapter() {
            @Override
            public void onTextAvailable(ProcessEvent event, Key outputType) {
                String raw = event.getText();
                if (raw == null) return;
                String line = raw.trim();

                // Always echo raw line to console so you SEE something
                System.out.println("[AI4SE Python RAW] " + line);

                // Detect JSON messages from Python stdout
                if (line.startsWith("{") && line.endsWith("}")) {
                    try {
                        JSONObject obj = new JSONObject(line);
                        String type = obj.optString("type", "");

                        switch (type) {
                            case "gaze": {
                                double leftX  = obj.optDouble("leftX", Double.NaN);
                                double leftY  = obj.optDouble("leftY", Double.NaN);
                                double rightX = obj.optDouble("rightX", Double.NaN);
                                double rightY = obj.optDouble("rightY", Double.NaN);

                                int leftValidity  = obj.optInt("leftValidity", -1);
                                int rightValidity = obj.optInt("rightValidity", -1);

                                boolean leftValid  =
                                        leftValidity == 1 && !Double.isNaN(leftX) && !Double.isNaN(leftY);
                                boolean rightValid =
                                        rightValidity == 1 && !Double.isNaN(rightX) && !Double.isNaN(rightY);

                                double gx = Double.NaN;
                                double gy = Double.NaN;

                                if (leftValid && rightValid) {
                                    gx = (leftX + rightX) / 2.0;
                                    gy = (leftY + rightY) / 2.0;
                                } else if (leftValid) {
                                    gx = leftX;
                                    gy = leftY;
                                } else if (rightValid) {
                                    gx = rightX;
                                    gy = rightY;
                                }

                                double ts = obj.optDouble("timestamp", Double.NaN);

                                LOG.info(String.format(
                                        "[AI4SE] Gaze frame ts=%.0f left=(%.3f, %.3f, v=%d) right=(%.3f, %.3f, v=%d) -> avg=(%.3f, %.3f)",
                                        ts, leftX, leftY, leftValidity, rightX, rightY, rightValidity, gx, gy
                                ));

                                double finalGx = gx;
                                double finalGy = gy;
                                boolean finalLeftValid = leftValid;
                                boolean finalRightValid = rightValid;

                                SwingUtilities.invokeLater(() -> {
                                    if (project == null) {
                                        return;
                                    }

                                    if (eyeTracker != null) {
                                        ApplicationManager.getApplication().runReadAction(
                                                (Computable<Void>) () -> {
                                                    eyeTracker.processRawJson(project, obj.toString());
                                                    return null;
                                                }
                                        );
                                    } else {
                                        ApplicationManager.getApplication().runReadAction(
                                                (Computable<Void>) () -> {
                                                    EditorGazeMapper.mapGazeToEditor(project, finalGx, finalGy);
                                                    return null;
                                                }
                                        );
                                    }
                                });


                                break;
                            }

                            case "status": {
                                String status = obj.optString("status", "unknown");
                                LOG.info("[AI4SE] Status from Python: " + status + " -> " + obj);
                                System.out.println("[AI4SE] Status: " + status + " -> " + obj);
                                break;
                            }

                            case "error": {
                                String errorType = obj.optString("errorType", "unknown_error");
                                String msg = obj.optString("message", "");
                                LOG.warn("[AI4SE] Error from Python: " + errorType + " -> " + obj);
                                System.err.println("[AI4SE] Python error: " + errorType + " -> " + msg);
                                break;
                            }

                            default: {
                                LOG.info("[AI4SE] Unknown JSON type: " + type + " -> " + obj);
                                System.out.println("[AI4SE] Unknown JSON type: " + type + " -> " + obj);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        LOG.debug("Failed to parse JSON line: " + line, e);
                        System.err.println("[AI4SE] Failed to parse JSON: " + line);
                    }
                } else {
                    // Non-JSON output from container
                    LOG.info(prefix + line);
                    System.out.println(prefix + line);
                }
            }

            @Override
            public void processTerminated(ProcessEvent event) {
                LOG.info(prefix + "terminated with exit code " + event.getExitCode());
                System.out.println(prefix + "terminated with exit code " + event.getExitCode());
            }
        });

        runHandler.startNotify();
    }

    private void stopHandlerIfAny() {
        if (runHandler != null) {
            runHandler.destroyProcess(); // closes streams and stops pumping
            runHandler = null;
        }
    }

    // -------------------- Environment helpers --------------------

    private static void ensureDockerInstalled() throws IOException {
        Process p = new ProcessBuilder("docker", "--version").start();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                throw new IOException("docker --version timed out");
            }
            if (p.exitValue() != 0) {
                throw new IOException("Docker not available (exit " + p.exitValue() + ")");
            }
        } catch (InterruptedException e) {
            throw new IOException("Docker version check interrupted", e);
        }
    }

    private static boolean imageExists(String tag) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("docker", "image", "inspect", tag).start();
        return p.waitFor() == 0;
    }

    /** Find an available ephemeral host port. */
    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    // -------------------- UI hook (replace with your Tool Window update) --------------------

    /** Receives normalized averaged gaze (0..1 on display) */
    private void updateUi(double x, double y) {
        LOG.info(String.format("[AI4SE] Gaze avg: x=%.3f  y=%.3f", x, y));
        System.out.println("[AI4SE] Gaze avg: x=" + x + " y=" + y);
        // TODO: hook this into your ToolWindow instead of just logging
    }

    // -------------------- Disposable --------------------

    @Override
    public void dispose() {
        try { stop(); } catch (Exception ignored) {}
    }
}
