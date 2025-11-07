package org.dinataing.eyetrackingai4selab.eyetracking.runtime;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.List;

public final class ResourceExtractor {
    private ResourceExtractor() {}

    public static Path stageDockerContext(Class<?> scope) throws IOException {
        Path dir = Files.createTempDirectory("ai4se_docker_");
        copy(scope, "/docker/Dockerfile", dir.resolve("Dockerfile"));
        copy(scope, "/docker/requirements.txt", dir.resolve("requirements.txt"));
        copy(scope, "/docker/eyetracker.py", dir.resolve("eyetracker.py"));
        return dir;
    }

    public static String sha256(Path root, List<String> files) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String f : files) {
            byte[] bytes = Files.readAllBytes(root.resolve(f));
            md.update(bytes);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void copy(Class<?> scope, String resPath, Path out) throws IOException {
        try (InputStream in = scope.getResourceAsStream(resPath)) {
            if (in == null) throw new FileNotFoundException("Missing resource: " + resPath);
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
