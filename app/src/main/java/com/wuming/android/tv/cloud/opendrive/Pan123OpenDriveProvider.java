package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;
import java.util.List;
import java.util.Map;

/** App compatibility facade; protocol and workflows are owned by cloud-core. */
final class Pan123OpenDriveProvider extends AbstractOpenDriveProvider {
    private final Pan123Provider shared = AndroidPan123Provider.create();
    @Override public String id() { return shared.id(); }
    @Override public String displayName() { return shared.displayName(); }
    @Override public boolean available() { return shared.available(); }
    @Override public String availabilityMessage() { return shared.availabilityMessage(); }
    @Override public boolean authenticated() { return shared.authenticated(); }
    @Override public OpenDriveLoginChallenge beginLogin() { return shared.beginLogin(); }
    @Override public OpenDriveLoginState pollLogin(String sessionId) { return shared.pollLogin(sessionId); }
    @Override public List<OpenDriveItem> list(String parentId) throws Exception { return shared.list(parentId); }
    @Override public boolean supportsShare(String url) { return shared.supportsShare(url); }
    @Override public List<OpenDriveItem> listShare(String url) throws Exception { return shared.listShare(url); }
    @Override public OpenDrivePreparedExternal prepareExternal(OpenDriveExternalPayload payload) {
        var prepared = shared.prepareExternal(payload.corePayload());
        return prepared == null ? null : OpenDrivePreparedExternal.of(prepared::materialize);
    }
    @Override public OpenDrivePlayback resolve(String itemId) throws Exception { return shared.resolve(itemId); }
    @Override public void credentialsRestored() { shared.credentialsRestored(); }
    @Override public void logout() { shared.logout(); }
    @Override public boolean persistHandle(String handle) { return shared.persistHandle(handle); }
    static String canonicalShareUrl(String value) { return Pan123Provider.canonicalShareUrl(value); }
    static Map<String, String> shareHeaders() { return Pan123Provider.shareHeaders(); }
    static Map<String, String> shareDownloadHeaders(String token) { return Pan123Provider.shareDownloadHeaders(token); }
}
