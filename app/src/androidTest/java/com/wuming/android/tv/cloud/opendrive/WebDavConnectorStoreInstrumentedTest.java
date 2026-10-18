package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.bean.Drive;
import com.wuming.android.tv.db.AppDatabase;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class WebDavConnectorStoreInstrumentedTest {

    @Test
    public void savesCredentialsInKeystoreVaultAndDeletesThemWithConnector() {
        WebDavConnectorStore store = new WebDavConnectorStore();
        WebDavConnectorStore.Connector connector = store.save(null,
                "Instrumentation WebDAV", "https://dav.example.test/dav", "Media",
                "device-user", "device-secret", false);
        try {
            assertTrue(connector.id() > 0);
            assertFalse(connector.tokenRef().isBlank());

            Drive row = AppDatabase.get().getDriveDao().getById(connector.id());
            assertNotNull(row);
            assertFalse(row.configJson.contains("device-user"));
            assertFalse(row.configJson.contains("device-secret"));

            WebDavConnectorStore.Connector restored = store.get(connector.id());
            assertNotNull(restored);
            assertEquals("device-user", restored.username());
            assertEquals("device-secret", restored.password());
        } finally {
            String reference = connector.tokenRef();
            store.delete(connector.id());
            assertTrue(OpenDriveTokenVault.get(reference).isEmpty());
        }
    }
}
