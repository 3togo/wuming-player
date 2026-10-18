package com.wuming.android.tv;
import com.wuming.android.tv.setting.AppSettings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public class SettingTlsPolicyTest {

    @Test
    public void freshInstallUsesPlatformCertificateValidation() {
        assertFalse(AppSettings.initialLegacyTlsValue(Collections.emptySet()));
    }

    @Test
    public void existingInstallRetainsLegacyCompatibility() {
        assertTrue(AppSettings.initialLegacyTlsValue(Set.of("site_mode")));
    }

    @Test
    public void migrationKeysAloneDoNotLookLikeExistingConfiguration() {
        assertFalse(AppSettings.initialLegacyTlsValue(
                Set.of("legacy_tls", "tls_policy_initialized")));
    }
}
