package com.abdy2.aotvloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Fetches the published pathfinder jar and stages it for installation.
 *
 * <p>Downloading and installing are separate steps because they cannot happen at the same moment.
 * Fabric holds every jar in {@code mods/} open for the length of the session, and an open jar
 * cannot be replaced on Windows, so the file fetched during a run is written to a staging folder
 * and moved into place early in the following launch. That is the familiar shape: one launch picks
 * up the update, the next one runs it.
 *
 * <p>Failing to reach GitHub is not an error. Whatever is already installed keeps running, and the
 * check is simply retried next time.
 */
final class JarSource {
    private static final String PROPERTIES_FILE = "loader.properties";
    private static final String KEY_URL = "jar_url";

    private static final String DEFAULT_URL =
        "https://raw.githubusercontent.com/abdyzam50-rgb/pathfinding."
            + "/claude/fix-node-maps-lteNu/releases/aotvpathfinder-1.0.0.jar";

    /** Kept short: this runs while the player is watching a loading screen. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    /** Name the jar is installed under. Fixed, so an update replaces rather than accumulates. */
    static final String JAR_NAME = "aotvpathfinder.jar";

    private final Path modsDir;
    private final Path workDir;

    JarSource(Path modsDir, Path workDir) {
        this.modsDir = modsDir;
        this.workDir = workDir;
    }

    Path installedJar() {
        return modsDir.resolve(JAR_NAME);
    }

    Path stagedJar() {
        return workDir.resolve("staged-" + JAR_NAME);
    }

    /** Where a jar that could not be overwritten is parked until it can be deleted. */
    Path supersededDir() {
        return workDir.resolve("superseded");
    }

    enum Outcome {
        /** A newer build was fetched and is waiting for the next launch. */
        STAGED,
        /** What is installed already matches what is published. */
        ALREADY_CURRENT,
        /** An update was fetched earlier and is still waiting to be installed. */
        ALREADY_STAGED,
        /** Could not reach GitHub. Whatever is installed keeps running. */
        UNREACHABLE
    }

    record Result(Outcome outcome, String detail) {}

    /** Checks GitHub and stages the jar if it differs from what is installed. */
    Result checkForUpdate() {
        String url = configuredUrl();
        try {
            byte[] published = download(url);
            String publishedHash = shortHash(sha256(published));

            byte[] installedHash = hashOf(installedJar());
            if (installedHash != null && MessageDigest.isEqual(installedHash, sha256(published))) {
                // Installed copy is current; drop any stale staged file so it cannot be
                // installed over a newer jar later.
                Files.deleteIfExists(stagedJar());
                return new Result(Outcome.ALREADY_CURRENT, publishedHash);
            }

            byte[] stagedHash = hashOf(stagedJar());
            if (stagedHash != null && MessageDigest.isEqual(stagedHash, sha256(published))) {
                return new Result(Outcome.ALREADY_STAGED, publishedHash);
            }

            Files.createDirectories(workDir);
            // Write beside the target and move, so an interrupted download cannot leave a
            // truncated file that would later be installed.
            Path partial = workDir.resolve("download.part");
            Files.write(partial, published);
            Files.move(partial, stagedJar(), StandardCopyOption.REPLACE_EXISTING);
            return new Result(Outcome.STAGED, publishedHash);
        } catch (Exception e) {
            String why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return new Result(Outcome.UNREACHABLE, why);
        }
    }

    private byte[] download(String url) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();

            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            byte[] body = response.body();
            if (!looksLikeJar(body)) {
                throw new IOException("response was not a jar (" + body.length + " bytes)");
            }
            return body;
        }
    }

    /** Guards against an error page or redirect being staged and later installed as a mod. */
    private static boolean looksLikeJar(byte[] data) {
        return data.length > 512
            && data[0] == 'P' && data[1] == 'K'
            && data[2] == 3 && data[3] == 4;
    }

    /** Where to fetch from, overridable so a different branch or fork can be tracked. */
    private String configuredUrl() {
        Path file = workDir.resolve(PROPERTIES_FILE);
        try {
            if (Files.isRegularFile(file)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
                String value = props.getProperty(KEY_URL);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            } else {
                Files.createDirectories(workDir);
                Files.writeString(file,
                    "# Where the loader fetches the pathfinder jar from.\n"
                        + "# Point this at another branch or a fork to track that instead.\n"
                        + KEY_URL + "=" + DEFAULT_URL + "\n",
                    StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // A missing or unreadable config just means the default is used.
        }
        return DEFAULT_URL;
    }

    static byte[] hashOf(Path file) {
        try {
            return Files.isRegularFile(file) ? sha256(Files.readAllBytes(file)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static String shortHash(byte[] hash) {
        return HexFormat.of().formatHex(hash).substring(0, 12);
    }
}
