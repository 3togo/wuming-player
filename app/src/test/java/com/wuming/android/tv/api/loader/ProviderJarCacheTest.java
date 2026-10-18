package com.wuming.android.tv.api.loader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.wuming.tv.core.source.SpiderDigest;
import com.wuming.tv.core.source.SpiderDigestKind;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProviderJarCacheTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void recoversOnlyInitializedCopyWithMatchingRecordedAndConfiguredDigest() throws Exception {
        File artifact = jar("provider.jar", new byte[]{1, 2, 3});
        ProviderJarCache cache = new ProviderJarCache(temp.getRoot(), "a".repeat(32));
        String sha256 = hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact.toPath())));
        SpiderDigest pin = new SpiderDigest(SpiderDigestKind.SHA256, sha256);

        assertNull(cache.recover(pin));
        assertTrue(cache.accepts(artifact, pin));
        cache.promote(artifact, pin);
        assertNotNull(cache.recover(pin));
        assertNull(new ProviderJarCache(temp.getRoot(), "d".repeat(32)).recover(pin));
        assertNull(cache.recover(new SpiderDigest(SpiderDigestKind.SHA256, "b".repeat(64))));

        Files.writeString(temp.getRoot().toPath().resolve("a".repeat(32) + ".good.sha256"), "c".repeat(64));
        assertNull(cache.recover(pin));

        cache.promote(artifact, pin);
        File retained = cache.recover(pin);
        assertNotNull(retained);
        Files.copy(jar("tampered.jar", new byte[]{9}).toPath(), retained.toPath(), StandardCopyOption.REPLACE_EXISTING);
        assertNull(cache.recover(pin));
        assertNull(cache.recover(null));
    }

    @Test
    public void rejectedUpdateCannotReplaceLastGoodArtifact() throws Exception {
        ProviderJarCache cache = new ProviderJarCache(temp.getRoot(), "b".repeat(32));
        File good = jar("good.jar", new byte[]{1});
        File update = jar("update.jar", new byte[]{2});
        String sha256 = hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(good.toPath())));
        SpiderDigest pin = new SpiderDigest(SpiderDigestKind.SHA256, sha256);
        cache.promote(good, pin);

        assertFalse(cache.accepts(update, pin));
        assertThrows(java.io.IOException.class, () -> cache.promote(update, pin));
        assertNotNull(cache.recover(pin));
        assertFalse(cache.accepts(temp.newFile("html.jar"), null));
    }

    private File jar(String name, byte[] dex) throws Exception {
        File file = temp.newFile(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            output.putNextEntry(new ZipEntry("classes.dex"));
            output.write(new byte[]{'d', 'e', 'x', '\n', '0', '3', '5', 0});
            output.write(dex);
            output.closeEntry();
        }
        return file;
    }

    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format("%02x", b & 0xFF));
        return value.toString();
    }
}
