# Targeted Kotlin conversions — 2026-09-16

## Scope

Implemented the five recommended areas as a bounded delivery: **38 production files** (36 Android, two desktop). This is not a conversion of every remaining Java file. The ten larger legacy Room entities and AppDatabase retain their Java contracts; native/plugin bridges, provider engines, and playback/activity hierarchies were outside the suggested first targets.

- Desktop: CastUris and OkHttpStreamClient.
- Playback metadata: PlaySpec.
- Media/model helpers: HighestQualityVariantTransform, UriRewriteTransform, HttpSubtitleDownloader, SubtitleSearchEngine, Cate.
- QR/casting: all nine files from audit group B2.
- Persistence/image processing: all 15 DAO files, SearchHistory, CloudAuditEvent, CloudCacheRecord, MediaResourceEntity, MediaSourceEntity, and OkGlideModule.

## Build and compatibility

Room 2.8.4 and Glide 4.16.0 now use KSP2 2.3.12. Room continues generating Java implementations, allowing generated output and existing Java callers to exercise the Kotlin interfaces. EventBus retains its existing Java processor and reflection-based runtime setup.

An initial legacy-kapt trial failed because its generated Java stubs extended java.lang.Record for existing Kotlin record types. No record types were changed to accommodate that failure; KSP2 is the final configuration.

The five entity conversions preserve field names, defaults, public fields/accessors, column nullability, indices and foreign keys. Version 44 and its exported schema remain unchanged. Glide's generated registration includes the app module and the existing AVIF/OkHttp modules. There are no app-manifest Glide modules to carry over.

DAO collection signatures retain Java override compatibility via @JvmSuppressWildcards. Boxed Long insert results remain boxed. Existing Java tests remain Java. KotlinDaoJavaContractTest adds coverage for Java overrides, conflict-only updates, boxed/generic signatures, and the default-method bridge used by Room's generated Java.

QR code retains measured sizing, weak activity references, request generations, synchronous cancellation/bitmap release, package/host allowlists, bounded scanning and owned-image cleanup. Worker QR encoding now calls the stateless QR utility directly so the worker does not capture its dialog merely to encode the bitmap. Casting retains a dedicated LAN transport, acknowledgement handling and one bounded retry.

## Validation

Final results ([machine-readable evidence](../../PUBLIC_PREPARATION.md)):

| Check | Result |
| --- | --- |
| Mobile JVM suite | 821 tests: 820 passed, one skipped |
| Leanback JVM suite | 828 tests: 827 passed, one skipped |
| Desktop JVM suite | 182 tests: 177 passed, five skipped |
| Android production compilation | All six mobile/leanback/leanback-lite ARM64/ARM32 verification variants passed |
| Verification app packaging | Mobile ARM64 and leanback ARM32 APKs built |
| Instrumentation packaging | Mobile and leanback ARM64 runner APKs built |
| Java API comparison | 37 public Android types per flavor; no missing or changed public/protected member signatures |
| Database schemas | All ten tracked schema exports byte-for-byte unchanged |
| Boundary checks | Generic libraries, desktop and code lifecycle checks passed |
| TV source checks | 121 layouts, 282 explicit focus targets, 26 action contracts, 150 listener checks passed |
| Formatting | Changed Kotlin formatted with ktlint; git diff --check passed |

The API comparison compiled the original Java from the source base separately and compared JVM descriptors, generic declarations and overridability against the new classes. It is a focused callable-interface comparison, not a complete runtime/nullability proof.

 Compilation and JVM/source checks do not establish real-provider login, physical remote, casting-device, or Android database runtime behavior. No device was accessed in this delivery. Chaquopy is disabled by the repository configuration.

## Converted files

- `app/src/main/java/com/fongmi/android/tv/bean/Cate.kt`
- `app/src/main/java/com/fongmi/android/tv/cloud/CloudAuditEvent.kt`
- `app/src/main/java/com/fongmi/android/tv/cloud/CloudCacheRecord.kt`
- `app/src/main/java/com/fongmi/android/tv/db/SearchHistory.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/BaseDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/CloudAuditDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/CloudCacheDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/ConfigDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/DeviceDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/DriveDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/HistoryDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/KeepDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/LiveDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/MediaCatalogDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/SearchHistoryDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/SiteDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/SitePerformanceDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/TrackDao.kt`
- `app/src/main/java/com/fongmi/android/tv/db/dao/ViewingRecordDao.kt`
- `app/src/main/java/com/fongmi/android/tv/media/catalog/MediaResourceEntity.kt`
- `app/src/main/java/com/fongmi/android/tv/media/catalog/MediaSourceEntity.kt`
- `app/src/main/java/com/fongmi/android/tv/media/hls/HighestQualityVariantTransform.kt`
- `app/src/main/java/com/fongmi/android/tv/media/hls/UriRewriteTransform.kt`
- `app/src/main/java/com/fongmi/android/tv/media/subtitle/HttpSubtitleDownloader.kt`
- `app/src/main/java/com/fongmi/android/tv/media/subtitle/SubtitleSearchEngine.kt`
- `app/src/main/java/com/fongmi/android/tv/player/engine/PlaySpec.kt`
- `app/src/main/java/com/fongmi/android/tv/ui/dialog/BaseQrLoginDialog.kt`
- `app/src/main/java/com/fongmi/android/tv/ui/dialog/BilibiliLoginDialog.kt`
- `app/src/main/java/com/fongmi/android/tv/ui/dialog/CastDialogV2.kt`
- `app/src/main/java/com/fongmi/android/tv/ui/dialog/OpenDriveLoginDialog.kt`
- `app/src/main/java/com/fongmi/android/tv/utils/OkGlideModule.kt`
- `app/src/main/java/com/fongmi/android/tv/utils/OpenDriveQrHandoff.kt`
- `app/src/main/java/com/fongmi/android/tv/utils/ProviderDialog.kt`
- `app/src/main/java/com/fongmi/android/tv/utils/ProviderQrLauncher.kt`
- `app/src/main/java/com/fongmi/android/tv/utils/QrSizing.kt`
- `app/src/main/java/com/fongmi/android/tv/utils/TvDialogFocus.kt`
- `desktop/src/main/java/com/fongmi/tv/desktop/cast/CastUris.kt`
- `desktop/src/main/java/com/fongmi/tv/desktop/cast/OkHttpStreamClient.kt`
