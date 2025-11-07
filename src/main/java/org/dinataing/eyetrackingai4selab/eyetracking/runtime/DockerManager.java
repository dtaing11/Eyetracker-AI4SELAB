package org.dinataing.eyetrackingai4selab.eyetracking.runtime;

import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
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

    private Process runProcess;
    private OSProcessHandler runHandler;
    private String imageTag;
    private int hostPort = -1; // chosen dynamically at start

    // -------------------- Public helpers (EDT-safe) --------------------

    /** Non-blocking start/build; safe to call from actions (EDT). */
    public void startOrBuildAndStartAsync(Project project) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI4SE: Starting Tracker", false) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    startOrBuildAndStart(); // runs off-EDT here
                } catch (Exception ex) {
                    LOG.warn("AI4SE start failed", ex);
                }
            }
        });
    }

    /** Non-blocking stop; safe to call from actions (EDT). */
    public void stopAsync(Project project) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI4SE: Stopping Tracker", false) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                stop(); // runs off-EDT here
            }
        });
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
        // If your entry script is eyetracker.py (not main.py), keep it here and in your Dockerfile CMD.
        String contentHash = ResourceExtractor
                .sha256(ctx, List.of("Dockerfile", "requirements.txt", "eyetracker.py"))
                .substring(0, 12);
        imageTag = IMAGE_BASE + ":" + contentHash;

        // Build image if missing
        if (!imageExists(imageTag)) {
            LOG.info("[AI4SE] building image " + imageTag);
            runAndCheckWithLogs(
                    new ProcessBuilder("docker", "build", "-t", imageTag, ctx.toString()),
                    "[AI4SE Build] "
            );
        } else {
            LOG.info("[AI4SE] image already present: " + imageTag);
        }

        if (isRunning()) {
            LOG.info("[AI4SE] container already running.");
            return;
        }

        // Best-effort cleanup of any stale container
        try {
            new ProcessBuilder("docker", "rm", "-f", CONTAINER_NAME)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        // Pick a free host port and map -> container:5000
        hostPort = findFreePort();

        // Run container
        List<String> cmd = Arrays.asList(
                "docker", "run", "--rm",
                "--name", CONTAINER_NAME,
                "-p", hostPort + ":" + CONTAINER_PORT,
                imageTag
        );
        String commandLine = String.join(" ", cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        runProcess = pb.start();

        // Capture logs and JSON messages safely
        startWithHandler(runProcess, commandLine, "[AI4SE Docker] ");
        LOG.info("[AI4SE] container starting, mapped to http://localhost:" + hostPort);
    }

    public synchronized void stop() {
        stopHandlerIfAny();

        if (isRunning()) {
            runProcess.destroy();
            try { runProcess.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            runProcess = null;
        }
        // Ensure stopped if docker detached for any reason
        try {
            new ProcessBuilder("docker", "stop", CONTAINER_NAME).start();
        } catch (IOException ignored) {}
        hostPort = -1;
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
                if (t != null) LOG.info(prefix + t.trim());
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
                String line = event.getText();
                if (line == null) return;
                line = line.trim();

                // Detect JSON messages (from Python stdout)
                if (line.startsWith("{") && line.endsWith("}")) {
                    try {
                        JSONObject obj = new JSONObject(line);
                        double x = obj.optDouble("eyeX", Double.NaN);
                        double y = obj.optDouble("eyeY", Double.NaN);
                        // Thread-safe UI update hook
                        SwingUtilities.invokeLater(() -> updateUi(x, y));
                    } catch (Exception e) {
                        LOG.debug("Failed to parse JSON line: " + line, e);
                    }
                } else {
                    LOG.info(prefix + line);
                }
            }

            @Override
            public void processTerminated(ProcessEvent event) {
                LOG.info(prefix + "terminated with exit code " + event.getExitCode());
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

    private void updateUi(double x, double y) {
        LOG.info(String.format("[AI4SE] Gaze: x=%.3f  y=%.3f", x, y));
        // For dev only; remove if you don't want console spam during runIde:
        System.out.println("[AI4SE] Gaze: x=" + x + " y=" + y);
    }

    // -------------------- Disposable --------------------

    @Override
    public void dispose() {
        try { stop(); } catch (Exception ignored) {}
    }
}
