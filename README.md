> **Source preview — not a stable APK release.** Start with
> [the preview guide](docs/public-preview.md) for build instructions and known gaps.
> Private depot synchronization, account test shares and original Git history are excluded.
> Updates are disabled until a publisher is configured. No private signing keys are included.

# wuming-player 開發者文件

TV 是基於 [CatVod](https://github.com/CatVodTVOfficial/CatVodTVJarLoader) 的開源影音應用程式。
專案提供 Android TV、精簡 TV、手機版本，以及獨立建置的 Linux 桌面基礎客戶端。

本快照尚未設立發布頻道或支援群組。上游專案與作者的連結僅供來源歸屬，
不代表其支援本快照。參見 [文件索引](docs/README.md)。

## 功能概覽

- 多站點點播、分類篩選、收藏、觀看記錄與無痕模式
- 多語片名搜尋，支援繁體轉簡體及英文片名對應正式中文片名
- 可選的嚴格搜尋模式，用於排除只在描述或無關欄位中命中的結果
- Media3 播放器、DRM、外掛字幕、彈幕、畫中畫及片頭片尾跳過
- M3U、TXT、JSON 直播來源、XMLTV EPG、追看與時移
- Java、JavaScript（QuickJS）及 Python（Chaquopy）爬蟲
- 手機端 DLNA 投放及 TV 端 DLNA Renderer
- DoH、代理、Hosts 覆寫、CORS 規則、廣告攔截及 WebView 嗅探
- 區域網路 HTTP API，可供遙控、推送字幕、彈幕及媒體

## 專案結構

| 項目 | 值 |
| --- | --- |
| Application ID | `com.wuming.android.tv.publicedition` |
| 最低版本 | Android 8.0 / API 26 |
| JVM source target | 17 |
| Gradle / lint JDK | 21 |
| ABI | `arm64-v8a`、`armeabi-v7a` |
| UI flavor | `leanback`、`leanback_lite`、`mobile` |

```text
TV/
├── app/       主應用程式及各 UI flavor
├── desktop/   獨立 Kotlin/JVM Linux 桌面客戶端
├── libraries/ 可由 Android 與桌面端共用的純 JVM 函式庫
├── catvod/    Spider 抽象層與網路基礎設施
├── chaquo/    Python 執行環境
├── quickjs/   JavaScript 執行環境
└── docs/      功能與協定文件
```

可跨平台的業務規則按領域位於 `libraries/` 的 `catalog-core`、`cloud-core`、`epg-core`、`live-core`、
`media-core`、`playback-core`、`search-core`、`source-core` 與 `user-library-core` JVM 函式庫，並使用
`com.wuming.tv.core` 命名空間。
Android 平台適配器及各 flavor 共用的 Android 程式碼位於 `app/src/main/`。TV 與手機 UI 分別位於 `app/src/leanback/` 和
`app/src/mobile/`；`leanback_lite` 重用 TV 原始碼並採用較低的 target SDK。完整依賴
方向見 [平台架構邊界](docs/architecture-boundaries.md)。

Linux 桌面基礎客戶端可獨立驗證及執行：

```bash
./gradlew :desktop:test
./gradlew :desktop:run --args='--smoke'
./gradlew :desktop:run
```

桌面端的 **Live TV** 頁面可載入本機或 HTTP(S) M3U／TVBox TXT 播放清單，瀏覽及篩選
頻道群組，選擇備用線路，並交由 mpv 或 VLC 播放。
**Catalogue** 頁面可與已配對的 Android TV 連線，使用電視端既有來源執行搜尋、詳情、
選集及直接媒體解析；私密供應商標頭只由電視端的限時媒體轉送使用。

## 建置與測試

請使用專案內的 Gradle Wrapper，並確保 Android Studio 支援版本目錄中宣告的 AGP。
不要以 IDE 建議的較新 AGP 覆蓋專案版本，除非同時驗證所有 flavor。

日常除錯只建置正在開發的介面與 CPU 架構（以下為 ARM64）：

```bash
# 手機
./gradlew :app:assembleMobileArm64_v8aDebug
# 電視
./gradlew :app:assembleLeanbackArm64_v8aDebug
# 精簡電視版
./gradlew :app:assembleLeanback_liteArm64_v8aDebug
```

單一 variant 的 APK 位於 `app/build/outputs/apk/` 對應子目錄。
32 位元 ARM 請將 task 名稱中的 `Arm64_v8a` 改成 `Armeabi_v7a`。
`./gradlew assembleDebug` 預設只建置 `mobile-arm64_v8a`、`leanback-armeabi_v7a`
兩個 app debug variant（及 helper app）；`assembleRelease` 也使用相同範圍。
需要全部六個 variant 時加上 `-PincludeAllVariants=true`。
上面的 leanback_lite 專用命令仍可直接使用，不需額外參數。
增量建置量測與模組拆分方向見 [建置效能](docs/build-performance.md)。

Gradle 會依中國大陸時區或 `zh_CN` 語系提示，預設使用 Aliyun Maven 鏡像，
不回退至 Google／Maven Central／Plugin Portal；此判斷不會探測網路。
可用 `-PuseChinaMirrors=true` 強制啟用，或 `-PuseChinaMirrors=false` 改用上游。
鏡像模式預設停用 Android SDK 自動下載，缺少的 SDK 套件請先以 SDK Manager 安裝。
時區／語系只是提示，並非實際所在地偵測。

完整的 `assembleDebug` 會將易於辨識的 app APK 複製到 `debug/`。若只需快速驗證
共用邏輯：

```bash
./gradlew testKeyLogic
```

其他常用驗證：

```bash
./gradlew unitAcceptanceBaseline
./gradlew assembleCriticalTests
./gradlew connectedCriticalTests
./gradlew testKeyLogicCoverage
```

`unitAcceptanceBaseline` 是唯一的功能驗收基準：它離線執行所有正式模組的 JVM／Python
單元測試與架構邊界檢查，不依賴網路、模擬器、實體裝置或視窗焦點。`assembleCriticalTests`
只編譯裝置整合測試；`connectedCriticalTests` 提供發布前硬體相容性證據，兩者都不取代或
決定單元測試驗收結果。

每個發布候選版本都必須在乾淨的提交上執行完整閘門：

```bash
./scripts/verify-release.sh
```

此閘門不需要 Android 裝置；它依序執行完整離線單元驗收、mobile 與 Leanback release
lint，以及手機 ARM64、TV ARM64 與 TV ARM32 三個已簽署、最佳化的 release APK 組裝。成功後會在
`app/build/reports/acceptance-baseline/` 寫入目前提交、工具鏈來源資料與三個 APK 的
SHA-256 校驗碼。Gradle 的 release 組裝任務也直接依賴 `unitAcceptanceBaseline`，因此
報告檔不能被當成跳過測試的憑證。手機與 TV 的硬體發布就緒檢查可在本地手動執行。

### GitHub CLI 是選用工具

GitHub CLI（`gh`）不是建置、測試、提交或推送的必要條件。
本專案的必要工具鏈是 Git、執行 Gradle/lint 的 JDK 21、JVM toolchain 使用的 JDK 17、專案內的 Gradle Wrapper，以及執行裝置測試時使用的
ADB。只要 Git 的 SSH 或 HTTPS 驗證可用，即使未安裝 `gh`、`gh auth status` 失敗，或其
權杖已過期，仍可正常建置及推送程式碼。本專案已移除 GitHub Actions 工作流程，
建置、測試與 APK 發布由本地工具手動執行。

`gh` 提供操作 pull request、issue 或 release 的便利介面；沒有 `gh` 時可直接使用
GitHub 網頁。私有儲存庫的 API 或 CLI 查詢需要另外登入，但該登入不得取代本地 release
閘門。

`connectedCriticalTests` 需要已授權且已連線的 Android 裝置，但不屬於功能驗收基準。所有 APK 共用使用者設定檔
`~/.config/wuming/config.json`（支援 `XDG_CONFIG_HOME` 與絕對路徑覆寫
`WUMING_CONFIG_FILE`）中的簽署金鑰。首次執行 `python3 scripts/setup-signing.py`；
已有 `local.properties` 簽署設定時，改用 `--migrate-local` 保留原有金鑰。
腳本同時產生專案根目錄的 `wuming-config.example.json` 範本（不含私密資料）。
重複執行會驗證既有金鑰並補齊範本。完整匯入、更新與備份說明見
[使用者建置設定與 APK 簽署](docs/build-user-config.md)。

## 搜尋行為

搜尋只會在使用者提交鍵盤搜尋動作、點選建議或歷史項目後開始；輸入文字本身只更新
建議，不會提前送出搜尋。

- 繁體中文會保留原始查詢，並增加簡體中文查詢。
- 英文片名會先搜尋英文，再透過片名目錄查找正式簡體中文別名。
- 每次搜尋最多使用三個去重後的查詢變體，避免放大站點請求。
- 搜尋頁及結果頁都有嚴格搜尋切換鈕；在結果頁切換時會清除舊結果並重新搜尋。
- 嚴格與寬鬆模式使用不同快取，避免結果互相污染。

例如 `Pretty Woman` 可對應 `風月俏佳人`／`风月俏佳人`，而不是進行逐字翻譯。完整
流程、降級行為及維護方式見 [多語片名搜尋](docs/search.md)。

## 配置與擴充

Vod 配置是主要入口，可由 URL 或本地路徑載入，包含：

- 點播站點（`sites`）與解析規則（`parses`）
- 直播來源（`lives`）
- 網路設定（`doh`、`proxy`、`hosts`、`ads`）

Live 配置可內嵌於 Vod 配置或獨立存放。欄位定義請參閱
[config.md](docs/config.md)，爬蟲介面請參閱 [spider.md](docs/spider.md)。

## 變更前檢查

修改 layout、螢幕旋轉、Provider 行為、配置備份或檔案選擇前，請先閱讀
[Regression Prevention Playbook](PUBLIC_PREPARATION.md)。
手機直向、手機橫向及 Leanback 各有獨立的版面與焦點導覽契約，不應直接互相複製
source set。

完整主題列表與文件用途見 [docs/README.md](docs/README.md)。
