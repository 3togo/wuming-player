package com.wuming.android.tv.cloud.opendrive;

import com.wuming.android.tv.cloud.CloudCacheRecord;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.wuming.android.tv.cloud.CloudStorageRepository;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/** Only tracked copies of shared originals; smart playback never enters this cache. */
final class AliSharedOriginalCache {
    private static final Object COPY_LOCK = new Object();
    static final String OWNER = "opendrive:ali-original:";
    static final long LEASE_MS = 15 * 60_000L;
    private final Requester requester;
    private final Store store;
    private final LongSupplier clock;
    private final Set<String> created = new HashSet<>();

    AliSharedOriginalCache(Requester requester) {
        this(requester, new Store() {
            public List<CloudCacheRecord> active() { return CloudStorageRepository.active(CloudStorageProvider.ALIPAN); }
            public void record(CloudCacheRecord value) { CloudStorageRepository.record(value); }
            public void deleted(CloudCacheRecord value) {
                CloudStorageRepository.markDeleted(CloudStorageProvider.ALIPAN, value.accountHash, value.remoteFileId);
            }
        }, System::currentTimeMillis);
    }

    AliSharedOriginalCache(Requester requester, Store store, LongSupplier clock) {
        this.requester = requester;
        this.store = store;
        this.clock = clock;
    }

    JsonObject resolve(String drive, String share, String sourceFile, String shareToken, long size)
            throws Exception {
        synchronized (COPY_LOCK) { return resolveLocked(drive, share, sourceFile, shareToken, size); }
    }

    private JsonObject resolveLocked(String drive, String share, String sourceFile, String shareToken, long size)
            throws Exception {
        String account = hash(drive);
        String source = OWNER + hash(share + ":" + sourceFile);
        for (CloudCacheRecord record : owned(account)) {
            if (!source.equals(record.sourceSiteKey)) continue;
            // A prior successful 204 trash could leave an active row in older builds.
            // Ali answers 403 for that deleted file, not reliably 404. Verify the
            // recorded parent before reuse instead of broadening 403 into blind saves.
            boolean present = children(drive, record.remoteFolderId).stream()
                    .anyMatch(item -> record.remoteFileId.equals(OpenDriveHttp.string(item, "file_id")));
            if (!present) { store.deleted(record); continue; }
            try {
                JsonObject result = download(drive, record.remoteFileId);
                record.updatedAt = clock.getAsLong();
                store.record(record);
                return result;
            } catch (OpenDriveHttp.StatusException error) {
                if (error.code() != 404) throw error;
                store.deleted(record); // Missing copy, not permission to delete another file.
            }
        }
        // No timer deletes a playing file. Reclaim only expired leases when another
        // original is selected; never sweep unknown files or the whole recycle bin.
        for (CloudCacheRecord record : owned(account)) {
            if (com.wuming.tv.core.cloud.SavedCopyPolicy.expired(record.updatedAt, clock.getAsLong(), LEASE_MS)) {
                try { deleteTracked(drive, record); } catch (Exception ignored) { }
            }
        }
        if (owned(account).size() >= 10) throw new IOException("Ali original cache is busy; clear app cache or try later");
        String folder = folder(drive);
        JsonObject copy = OpenDriveHttp.json("share_id", share, "file_id", sourceFile,
                "to_drive_id", drive, "to_parent_file_id", folder, "auto_rename", true, "overwrite", false);
        JsonObject request = OpenDriveHttp.json("id", "0", "method", "POST", "url", "/file/copy");
        request.add("body", copy);
        request.add("headers", OpenDriveHttp.json("Content-Type", "application/json"));
        JsonArray requests = new JsonArray(); requests.add(request);
        JsonObject batch = OpenDriveHttp.json("resource", "file"); batch.add("requests", requests);
        JsonObject root = requester.post("adrive/v4/batch", batch, shareToken);
        JsonArray responses = root.getAsJsonArray("responses");
        if (responses == null || responses.size() != 1 || !responses.get(0).isJsonObject())
            throw new IOException("Ali copy returned no result");
        JsonObject response = responses.get(0).getAsJsonObject();
        long status = OpenDriveHttp.number(response, "status");
        if (status < 200 || status >= 300) throw new IOException("Ali could not save original (HTTP " + status + ")");
        String saved = OpenDriveHttp.string(OpenDriveHttp.object(response, "body"), "file_id");
        if (saved.isBlank()) throw new IOException("Ali copy omitted destination");
        CloudCacheRecord record = CloudCacheRecord.create(CloudStorageProvider.ALIPAN, account,
                folder, saved, "Shared original", source, size);
        record.createdAt = record.updatedAt = clock.getAsLong();
        try {
            store.record(record);
            created.add(saved);
            OpenDriveCopyUsage.stage("alipan", account, saved);
        } catch (RuntimeException error) {
            try { trash(drive, saved); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        // Personal requests must not carry x-share-token: Ali rejects the mismatched
        // share context. The returned media is the copied ORIGINAL, never a preview.
        return download(drive, saved);
    }

    private JsonObject download(String drive, String file) throws Exception {
        JsonObject result = requester.post("v2/file/get_download_url",
                OpenDriveHttp.json("drive_id", drive, "file_id", file, "expire_sec", 900), "");
        if (OpenDriveHttp.validHttpsUrl(OpenDriveHttp.string(result, "url"))
                || OpenDriveHttp.validHttpsUrl(OpenDriveHttp.string(result, "download_url")))
            result.addProperty("__copy_lease", OpenDriveCopyUsage.stage("alipan", hash(drive), file));
        return result;
    }

    private String folder(String drive) throws Exception {
        for (JsonObject item : children(drive, "root")) {
            if ("folder".equals(OpenDriveHttp.string(item, "type"))
                    && "TVBoxCache".equals(OpenDriveHttp.string(item, "name")))
                return requiredId(item);
        }
        JsonObject result = requester.post("adrive/v2/file/createWithFolders", OpenDriveHttp.json(
                "drive_id", drive, "parent_file_id", "root", "type", "folder", "name", "TVBoxCache",
                "check_name_mode", "refuse"), "");
        return requiredId(result);
    }

    private List<JsonObject> children(String drive, String parent) throws Exception {
        var result = new java.util.ArrayList<JsonObject>();
        String marker = "";
        for (int page = 0; page < 20; page++) {
            JsonObject body = OpenDriveHttp.json("drive_id", drive, "parent_file_id", parent, "limit", 200);
            if (!marker.isBlank()) body.addProperty("marker", marker);
            JsonObject root = requester.post("adrive/v3/file/list", body, "");
            JsonArray values = root.getAsJsonArray("items");
            if (values == null) throw new IOException("Ali folder listing omitted items");
            for (JsonElement value : values) if (value.isJsonObject()) result.add(value.getAsJsonObject());
            marker = OpenDriveHttp.string(root, "next_marker");
            if (marker.isBlank()) return result;
        }
        throw new IOException("Ali cache folder listing exceeded limit");
    }

    void cleanupCreated(String drive, String file) throws Exception {
        synchronized (COPY_LOCK) { cleanupCreatedLocked(drive, file); }
    }
    private void cleanupCreatedLocked(String drive, String file) throws Exception {
        if (!created.contains(file)) return;
        for (CloudCacheRecord record : owned(hash(drive))) {
            if (file.equals(record.remoteFileId)) {
                OpenDriveCopyUsage.retire("alipan", file);
                deleteTracked(drive, record);
                if (!OpenDriveCopyUsage.busy("alipan", file)) created.remove(file);
                return;
            }
        }
    }

    Set<String> createdIds() { synchronized (COPY_LOCK) { return Set.copyOf(created); } }

    boolean deleteTracked(String drive, CloudCacheRecord record) throws Exception {
        synchronized (COPY_LOCK) { return deleteLocked(drive, record); }
    }

    private boolean deleteLocked(String drive, CloudCacheRecord record) throws Exception {
        if (!owned(hash(drive)).stream().anyMatch(value -> value.remoteFileId.equals(record.remoteFileId)
                && value.remoteFolderId.equals(record.remoteFolderId)))
            throw new IOException("Ali cleanup rejected an untracked or different-account file");
        // Verify it is still in the tracked folder. A user moving a copy elsewhere
        // removes it from automatic cleanup; folder names alone never establish ownership.
        boolean present = children(drive, record.remoteFolderId).stream()
                .anyMatch(item -> record.remoteFileId.equals(OpenDriveHttp.string(item, "file_id")));
        return OpenDriveCopyUsage.delete("alipan", hash(drive), record.remoteFileId, () -> {
            if (present) trash(drive, record.remoteFileId);
            store.deleted(record);
            return present;
        });
    }

    private void trash(String drive, String file) throws Exception {
        requester.post("v2/recyclebin/trash", OpenDriveHttp.json("drive_id", drive, "file_id", file), "");
    }

    private List<CloudCacheRecord> owned(String account) {
        return store.active().stream().filter(record -> com.wuming.tv.core.cloud.SavedCopyPolicy.owned("alipan", account, OWNER,
                record.provider, record.accountHash, record.sourceSiteKey, record.state == CloudCacheRecord.ACTIVE)).toList();
    }

    private static String requiredId(JsonObject value) throws IOException {
        String id = OpenDriveHttp.string(value, "file_id");
        if (id.isBlank()) throw new IOException("Ali folder response omitted its ID");
        return id;
    }

    static String hash(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return com.wuming.android.tv.utils.Hex.encodeLower(bytes);
    }

    interface Requester { JsonObject post(String path, JsonObject body, String shareToken) throws Exception; }
    interface Store {
        List<CloudCacheRecord> active();
        void record(CloudCacheRecord value);
        void deleted(CloudCacheRecord value);
    }
}
