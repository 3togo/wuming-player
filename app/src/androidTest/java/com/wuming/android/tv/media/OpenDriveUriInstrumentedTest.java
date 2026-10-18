package com.wuming.android.tv.media;

import com.wuming.tv.core.media.OpenDriveUri;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/** Validates the canonical OpenDrive URI contract on the Android runtime. */
@RunWith(AndroidJUnit4.class)
public class OpenDriveUriInstrumentedTest {

    @Test
    public void canonicalUrisRoundTripForEveryKindOnDevice() {
        for (OpenDriveUri.Kind kind : OpenDriveUri.Kind.values()) {
            OpenDriveUri expected = new OpenDriveUri(
                    "device.catalog", kind, "Resource_01.~copy");

            assertTrue(OpenDriveUri.isValid(expected.toString()));
            assertEquals(expected, OpenDriveUri.parse(expected.toString()));
        }
    }

    @Test
    public void malformedAndCapabilityBearingUrisFailClosedOnDevice() {
        List<String> invalid = Arrays.asList(
                null,
                "",
                "OPENDRIVE://local/asset/id",
                "opendrive://user@local/asset/id",
                "opendrive://local:443/asset/id",
                "opendrive://local/asset/id/extra",
                "opendrive://local/asset/%69d",
                "opendrive://local/asset/id%2Fextra",
                "opendrive://local/asset/id?token=capability",
                "opendrive://local/asset/id#fragment",
                "opendrive://bad..authority/asset/id",
                "opendrive://local/asset/资源");

        for (String value : invalid) {
            assertFalse("Accepted invalid OpenDrive URI: " + value,
                    OpenDriveUri.isValid(value));
        }
    }

    @Test
    public void locallyGeneratedUriIsCanonicalOnDevice() {
        OpenDriveUri uri = OpenDriveUri.local(OpenDriveUri.Kind.ASSET);

        assertEquals(OpenDriveUri.LOCAL_AUTHORITY, uri.authority());
        assertTrue(OpenDriveUri.isValid(uri.toString()));
        assertEquals(uri, OpenDriveUri.parse(uri.toString()));
    }
}
