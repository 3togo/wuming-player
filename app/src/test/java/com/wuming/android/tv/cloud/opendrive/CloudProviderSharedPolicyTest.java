package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the built-in cloud providers on the shared cache, capacity and media-classification
 * primitives. Every entry here used to exist as a per-provider copy, so a new copy has to be
 * a deliberate decision rather than a silent paste.
 */
public class CloudProviderSharedPolicyTest {

    private static final List<String> PROVIDERS = List.of(
            "AliPanOpenDriveProvider", "BaiduOpenDriveProvider", "Pan123OpenDriveProvider",
            "QuarkOpenDriveProvider", "TianyiOpenDriveProvider", "UcOpenDriveProvider",
            "WebDavOpenDriveProvider");

    private static final String PROVIDER_DIRECTORY =
            "src/main/java/com/wuming/android/tv/cloud/opendrive";

    /** Names with one owner only: the core classifier, the vault, or the provider base. */
    private static final List<String> SINGULAR = List.of(
            "VIDEO_EXTENSIONS", "QR_LOGIN_TTL_MILLIS", "QR_VERSION", "MAX_HANDLES");

    @Test
    public void noProviderRedeclaresASharedConstant() throws IOException {
        for (String provider : PROVIDERS) {
            for (String name : SINGULAR) {
                assertFalse(provider + " must not declare " + name,
                        declares(source(provider), name));
            }
        }
    }

    @Test
    public void handleCachesUseTheSharedCapacity() throws IOException {
        String call = "new OpenDriveMemoryCache<>(";
        for (String provider : PROVIDERS) {
            String text = source(provider);
            for (String argument : argumentsOf(text, call)) {
                assertTrue(provider + " must size its handle cache from the vault, got " + argument,
                        argument.contains("MAX_HANDLES") || argument.contains("MAX_MEMORY_ENTRIES"));
                assertFalse(provider + " must not inline a cache capacity: " + argument,
                        argument.matches(".*\\d.*"));
            }
        }
    }

    @Test
    public void everyProviderUsesTheCoreMediaClassifier() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String provider : PROVIDERS) {
            String text = source(provider);
            if (!text.contains("isVideo(")) continue;
            // Providers should delegate to OpenDriveHttp.isVideo or MediaFileClassifier directly
            if (!text.contains("MediaFileClassifier.") && !text.contains("OpenDriveHttp.isVideo")) {
                missing.add(provider);
            }
        }
        assertEquals("video names must come from :libraries:media-core", List.of(), missing);
    }

    /**
     * The narrow cloud allow-list and the broad playback list are two different policies on
     * purpose: drive listings show plain containers, while WebDAV/AIST mirrors a directory as-is.
     * Recording the split here stops a future edit from "unifying" them by accident.
     */
    @Test
    public void webDavStaysOnTheBroadPlaybackList() throws IOException {
        String text = source("WebDavOpenDriveProvider");
        assertTrue(text.contains("MediaFileClassifier.isVideoExtension"));
        // OpenDriveHttp.isVideo delegates to MediaFileClassifier.isCloudVideoFileName
        // so providers that call OpenDriveHttp.isVideo are on the strict cloud list.
        String http = source("OpenDriveHttp");
        assertTrue("OpenDriveHttp must keep the strict cloud list",
                http.contains("MediaFileClassifier.isCloudVideoFileName"));
        for (String provider : PROVIDERS) {
            if (provider.equals("WebDavOpenDriveProvider")) continue;
            String src = source(provider);
            if (!src.contains("isVideo(")) continue;
            // Providers with their own isVideo(String) helper must use MediaFileClassifier directly
            if (src.contains("private static boolean isVideo(String")) {
                assertTrue(provider + " must keep the strict cloud list",
                        src.contains("MediaFileClassifier.isCloudVideoFileName"));
            }
        }
    }

    private static boolean declares(String text, String name) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
            if (trimmed.matches(".*\\b(private|static)\\b.*\\b" + name + "\\b\\s*=.*")) return true;
        }
        return false;
    }

    private static List<String> argumentsOf(String text, String call) {
        List<String> arguments = new ArrayList<>();
        for (int start = text.indexOf(call); start >= 0; start = text.indexOf(call, start + call.length())) {
            int depth = 1;
            StringBuilder current = new StringBuilder();
            for (int index = start + call.length(); index < text.length(); index++) {
                char character = text.charAt(index);
                if (character == '(') depth++;
                if (character == ')') depth--;
                if (depth == 0) break;
                current.append(character);
            }
            arguments.add(current.toString().trim());
        }
        return arguments;
    }

    private static String source(String provider) throws IOException {
        Path file = appRoot().resolve(PROVIDER_DIRECTORY).resolve(provider + ".java");
        assertTrue("missing provider source " + file, Files.isRegularFile(file));
        return Files.readString(file);
    }

    private static Path appRoot() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (ownsProviderSources(directory)) return directory;
            Path nested = directory.resolve("app");
            if (ownsProviderSources(nested)) return nested;
        }
        throw new IllegalStateException("app module root not found");
    }

    private static boolean ownsProviderSources(Path candidate) {
        return Files.isDirectory(candidate.resolve(PROVIDER_DIRECTORY));
    }
}
