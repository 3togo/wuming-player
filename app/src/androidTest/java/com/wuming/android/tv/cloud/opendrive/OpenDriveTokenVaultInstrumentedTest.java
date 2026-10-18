package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public class OpenDriveTokenVaultInstrumentedTest {

    @Test
    public void credentialReplacementCommitsAsOneValidatedSet() {
        Map<String, String> previous = OpenDriveTokenVault.exportCredentials();
        String token = OpenDriveTokenVault.reference("backupcheck", "token");
        String connector = OpenDriveTokenVault.reference("webdav", "config.test_backup");
        Map<String, String> replacement = new LinkedHashMap<>();
        replacement.put(token, "test-token-secret");
        replacement.put(connector, "{\"password\":\"test-password\"}");
        try {
            assertTrue(OpenDriveTokenVault.replaceCredentials(replacement));
            assertEquals(replacement, OpenDriveTokenVault.exportCredentials());

            Map<String, String> invalid = new LinkedHashMap<>(replacement);
            invalid.put("not-a-vault-reference", "must-not-be-written");
            assertTrue(!OpenDriveTokenVault.replaceCredentials(invalid));
            assertEquals(replacement, OpenDriveTokenVault.exportCredentials());
        } finally {
            assertTrue(OpenDriveTokenVault.replaceCredentials(previous));
        }
    }

    @Test
    public void keystoreVaultRoundTripsOpaqueProviderMetadata() {
        String handle = UUID.randomUUID().toString();
        String reference = OpenDriveTokenVault.reference("quark", handle);
        String secretMetadata = "{\"shareToken\":\"must-not-enter-catalog\"}";
        try {
            assertTrue(OpenDriveTokenVault.put(reference, secretMetadata));
            assertEquals(secretMetadata, OpenDriveTokenVault.get(reference));
        } finally {
            OpenDriveTokenVault.remove(reference);
        }
    }

    @Test
    public void quarkHandleSurvivesAnInMemoryProviderRestart() throws Exception {
        QuarkOpenDriveProvider provider = new QuarkOpenDriveProvider();
        String payload = "{"
                + "\"sId\":\"e518f78e05f0\","
                + "\"sToken\":\"share/capability==\","
                + "\"share_fid_token\":\"38be57b5fe7edc510927340a14e22878\","
                + "\"fid\":\"0196c891698f43bb9d8b06168d40f336\","
                + "\"pdir_fid\":\"e00b422b9e754f7f966056ef9b7b2a84\","
                + "\"flag\":\"夸父盘\"}";
        String handle = provider.importExternal("夸父原1", payload);
        String reference = OpenDriveHandleVault.reference("quark", handle);
        try {
            assertTrue(provider.persistHandle(handle));
            Field memoryField = OpenDriveHandleVault.class.getDeclaredField("MEMORY");
            memoryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            OpenDriveMemoryCache<String, Object> memory =
                    (OpenDriveMemoryCache<String, Object>) memoryField.get(null);
            memory.remove(reference);

            assertTrue(new QuarkOpenDriveProvider().persistHandle(handle));
        } finally {
            OpenDriveHandleVault.remove(reference);
        }
    }

    @Test
    public void legacyCredentialHandleMigratesWithoutLosingTheToken() {
        String providerId = "migrationtest";
        String legacy = OpenDriveTokenVault.reference(providerId, "credential");
        String current = OpenDriveTokenVault.reference(providerId, "token");
        String token = "__pus=reference-session;__puus=refreshed-session";
        try {
            OpenDriveTokenStore.clear(providerId);
            assertTrue(OpenDriveTokenVault.put(legacy, token));

            assertEquals(token, OpenDriveTokenStore.get(providerId));
            assertEquals(token, OpenDriveTokenVault.get(current));
            assertTrue(OpenDriveTokenVault.get(legacy).isEmpty());
        } finally {
            OpenDriveTokenStore.clear(providerId);
        }
    }
}
