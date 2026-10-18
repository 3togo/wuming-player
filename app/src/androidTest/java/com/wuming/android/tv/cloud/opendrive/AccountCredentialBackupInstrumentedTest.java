package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.github.catvod.net.PersistentCookieJar;
import com.google.gson.Gson;
import com.wuming.tv.core.cloud.CredentialBackupCodec;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

@RunWith(AndroidJUnit4.class)
public class AccountCredentialBackupInstrumentedTest {
    private static final Gson GSON = new Gson();

    @Test
    public void encryptedFileRestoresCookiesTokensAndConnectorAfterReinstall() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<String, String> previous = AccountCredentialBackupState.capture();
        PersistentCookieJar jar = PersistentCookieJar.get();
        Map<String, String> otherCookies = jar.exportCookies(Set.of("bilibili.com"));
        File file = new File(context.getCacheDir(), "credential-backup-test.json");
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
        Map<String, String> empty = Map.of("cookies", "{}", "connectors", "[]");
        try {
            AccountCredentialBackupState.restore(empty);
            assertTrue(OpenDriveTokenVault.put(OpenDriveTokenVault.reference("backupfile", "token"),
                    "file-round-trip-secret"));
            new WebDavConnectorStore().save(null, "Backup connector", "https://example.com/dav/",
                    "movies", "alice", "connector-secret", false);
            HttpUrl url = HttpUrl.get("https://drive-pc.quark.cn/");
            jar.saveFromResponse(url, List.of(new Cookie.Builder().name("__pus")
                    .value("quark-secret").hostOnlyDomain(url.host()).path("/").secure()
                    .httpOnly().expiresAt(System.currentTimeMillis() + 600_000).build()));
            HttpUrl tianyi = HttpUrl.get("https://cloud.189.cn/");
            jar.saveFromResponse(tianyi, List.of(new Cookie.Builder().name("COOKIE_LOGIN_USER")
                    .value("tianyi-secret").domain("189.cn").path("/")
                    .expiresAt(System.currentTimeMillis() + 600_000).build()));
            // Bilibili sessions are part of this archive: the settings backup only carries
            // preferences and database rows, so it cannot restore them.
            jar.saveFromResponse(HttpUrl.get("https://www.bilibili.com/"), List.of(
                    new Cookie.Builder().name("SESSDATA").value("bili-session-secret")
                            .domain("bilibili.com").secure().httpOnly().build(),
                    new Cookie.Builder().name("bili_jct").value("bili-csrf-secret")
                            .domain("bilibili.com").build(),
                    new Cookie.Builder().name("DedeUserID").value("123456789")
                            .domain("bilibili.com").build()));
            Map<String, String> exported = AccountCredentialBackupState.capture();
            char[] password = "correct horse".toCharArray();
            int count = AccountCredentialBackupState.credentialCount(exported);
            assertEquals(count, AccountCredentialBackup.exportTo(context.getContentResolver(), uri, password));
            assertArrayEquals(new char[password.length], password);
            String envelope = Files.readString(file.toPath());
            for (String secret : List.of("file-round-trip-secret", "quark-secret", "tianyi-secret",
                    "connector-secret", "alice", "example.com", "bili-session-secret",
                    "bili-csrf-secret", "123456789")) {
                assertFalse(envelope.contains(secret));
            }
            assertTrue(exported.get("cookies").contains("bili-session-secret"));
            AccountCredentialBackupState.restore(empty);
            assertTrue(new WebDavConnectorStore().list().isEmpty());
            char[] wrong = "incorrect horse".toCharArray();
            assertThrows(Exception.class, () -> AccountCredentialBackup.importFrom(
                    context.getContentResolver(), uri, wrong));
            assertArrayEquals(new char[wrong.length], wrong);
            assertEquals(empty, AccountCredentialBackupState.capture());
            assertEquals(count, AccountCredentialBackup.importFrom(
                    context.getContentResolver(), uri, "correct horse".toCharArray()));
            assertEquals(exported, AccountCredentialBackupState.capture());
            WebDavConnectorStore.Connector connector = new WebDavConnectorStore().list().get(0);
            assertEquals("https://example.com/dav/", connector.baseUrl());
            assertEquals("alice", connector.username());
            assertEquals("connector-secret", connector.password());
            Cookie restored = jar.loadForRequest(url).get(0);
            assertTrue(restored.hostOnly());
            assertTrue(restored.secure());
            assertTrue(restored.httpOnly());
            assertTrue(jar.loadForRequest(HttpUrl.get("https://pan.quark.cn/")).isEmpty());
            assertTrue(jar.hasCookie(tianyi.toString(), "COOKIE_LOGIN_USER"));
            assertTrue(jar.hasCookie("https://www.bilibili.com/", "SESSDATA"));
            Map<String, String> restoredBilibili = new LinkedHashMap<>();
            for (Cookie cookie : jar.loadForRequest(HttpUrl.get("https://api.bilibili.com/"))) {
                restoredBilibili.put(cookie.name(), cookie.value());
                if (cookie.name().equals("SESSDATA")) {
                    assertTrue(cookie.secure());
                    assertTrue(cookie.httpOnly());
                }
            }
            assertEquals(Map.of("SESSDATA", "bili-session-secret", "bili_jct", "bili-csrf-secret",
                    "DedeUserID", "123456789"), restoredBilibili);
        } finally {
            AccountCredentialBackupState.restore(previous);
            jar.replaceCookies(Set.of("bilibili.com"), otherCookies);
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void invalidPayloadDoesNotChangeAnyAccountState() {
        Map<String, String> previous = AccountCredentialBackupState.capture();
        Map<String, String> invalid = new LinkedHashMap<>(previous);
        invalid.put("cookies", GSON.toJson(Map.of("evil.example|SID",
                "SID=secret;domain=evil.example;path=/")));
        assertThrows(IllegalArgumentException.class, () -> AccountCredentialBackupState.restore(invalid));
        assertEquals(previous, AccountCredentialBackupState.capture());
        invalid.put("cookies", "{}");
        invalid.put("connectors", "[{\"name\":\"broken\",\"url\":\"https://example.com\","
                + "\"tokenRef\":\"vault:opendrive/webdav/config.missing\"}]");
        assertThrows(IllegalArgumentException.class, () -> AccountCredentialBackupState.restore(invalid));
        assertEquals(previous, AccountCredentialBackupState.capture());
    }

    @Test
    public void exportDestinationSurvivesActivityRecreationWithoutSavingPassword() {
        try (androidx.test.core.app.ActivityScenario<com.wuming.android.tv.ui.activity.CloudStorageActivity>
                     scenario = androidx.test.core.app.ActivityScenario.launch(
                     com.wuming.android.tv.ui.activity.CloudStorageActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field uri = activity.getClass().getDeclaredField("pendingExportUri");
                    uri.setAccessible(true);
                    uri.set(activity, Uri.parse("content://backup-test/destination"));
                    java.lang.reflect.Method show = activity.getClass().getDeclaredMethod("showExportPassphrase");
                    show.setAccessible(true);
                    show.invoke(activity);
                    android.widget.EditText password = firstPassword(passwordDialog(activity).getWindow().getDecorView());
                    assertNotNull(password);
                    password.setText("do-not-persist-this");
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field uri = activity.getClass().getDeclaredField("pendingExportUri");
                    uri.setAccessible(true);
                    assertEquals(Uri.parse("content://backup-test/destination"), uri.get(activity));
                    androidx.appcompat.app.AlertDialog dialog = passwordDialog(activity);
                    assertTrue(dialog.isShowing());
                    android.widget.EditText password = firstPassword(dialog.getWindow().getDecorView());
                    assertEquals("", password.getText().toString());
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    private static android.widget.EditText firstPassword(android.view.View view) {
        if (view instanceof android.widget.EditText field) return field;
        if (view instanceof android.view.ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                android.widget.EditText field = firstPassword(group.getChildAt(index));
                if (field != null) return field;
            }
        }
        return null;
    }

    private static androidx.appcompat.app.AlertDialog passwordDialog(Object activity) throws Exception {
        java.lang.reflect.Field field = activity.getClass().getDeclaredField("passphraseDialog");
        field.setAccessible(true);
        return (androidx.appcompat.app.AlertDialog) field.get(activity);
    }

    @Test
    public void versionOneVaultOnlyBackupRemainsReadable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<String, String> previous = AccountCredentialBackupState.capture();
        File file = new File(context.getCacheDir(), "credential-backup-v1.json");
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
        try {
            Map<String, String> legacy = Map.of("vault:opendrive/backupfile/token", "legacy-secret");
            byte[] encoded = CredentialBackupCodec.encrypt(legacy, "correct horse".toCharArray());
            Files.writeString(file.toPath(), new String(encoded, StandardCharsets.UTF_8)
                    .replace("\"version\":2", "\"version\":1"));
            Arrays.fill(encoded, (byte) 0);
            assertEquals(1, AccountCredentialBackup.importFrom(context.getContentResolver(), uri,
                    "correct horse".toCharArray()));
            assertEquals(legacy, OpenDriveTokenVault.exportCredentials());
            assertEquals(previous.get("cookies"), AccountCredentialBackupState.capture().get("cookies"));
        } finally {
            AccountCredentialBackupState.restore(previous);
            Files.deleteIfExists(file.toPath());
        }
    }
}
