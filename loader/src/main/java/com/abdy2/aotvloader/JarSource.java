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
 * Keeps a local copy of the pathfinder jar in step with the one published on GitHub.
 *
 * <p>The copy deliberately lives outside {@code mods/}. Fabric opens every jar in that folder at
 * startup and holds it open, and on Windows an open jar cannot be replaced — so a mod that updates
 * itself in place can only ever take effect on the <em>next</em> launch. Keeping the jar somewhere
 * Fabric never looks means it is just a file, replaceable at any moment, and the update applies to
 * the run that downloaded it.
 *
 * <p>Being unable to reach GitHub is not an error. The cached jar is used instead, so the mod still
 * starts offline; only a genuinely absent cache stops it.
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

    private final Path directory;
    private final Path jar;

    JarSource(Path directory) {
        this.directory = directory;
        this.jar = directory.resolve("aotvpathfinder.jar");
    }

    /** The result of trying to bring the local copy up to date. */
    enum Outcome {
        /** Downloaded a build different from the one held locally. */
        UPDATED,
        /** The published build matches what is already here. */
        ALREADY_CURRENT,
        /** Could not reach GitHub, but a usable cached jar exists. */
        OFFLINE_USING_CACHE,
        /** Could not reach GitHub and nothing is cached. */
        UNAVAILABLE
    }

    record Result(Outcome outcome, Path jar, String detail) {
        boolean usable() {
            return jar != null;
        }
    }

    Result fetchLatest() {
        String url = configuredUrl();
        byte[] localHash = hashOf(jar);

        try {
            byte[] published = download(url);
            byte[] publishedHash = sha256(published);

            if (localHash != null && MessageDigest.isEqual(localHash, publishedHash)) {
                return new Result(Outcome.ALREADY_CURRENT, jar, shortHash(publishedHash));
            }

            // Write beside the target and move into place, so a failure part-way through leaves the
            // previous working jar untouched rather than a truncated one.
            Files.createDirectories(directory);
            Path staged = directory.resolve("aotvpathfinder.jar.part");
            Files.write(staged, published);
            Files.move(staged, jar, StandardCopyOption.REPLACE_EXISTING);
            return new Result(Outcome.UPDATED, jar, shortHash(publishedHash));
        } catch (Exception e) {
            String why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (Files.isRegularFile(jar)) {
                return new Result(Outcome.OFFLINE_USING_CACHE, jar, why);
            }
            return new Result(Outcome.UNAVAILABLE, null, why);
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

    /** Guards against a proxy or error page being written over a working jar. */
    private static boolean looksLikeJar(byte[] data) {
        return data.length > 512
            && data[0] == 'P' && data[1] == 'K'
            && data[2] == 3 && data[3] == 4;
    }

    /** Where to fetch from, overridable so a different branch or fork can be pointed at. */
    private String configuredUrl() {
        Path file = directory.resolve(PROPERTIES_FILE);
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
                Files.createDirectories(directory);
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

    private static byte[] hashOf(Path file) {
        try {
            return Files.isRegularFile(file) ? sha256(Files.readAllBytes(file)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String shortHash(byte[] hash) {
        return HexFormat.of().formatHex(hash).substring(0, 12);
    }
}
