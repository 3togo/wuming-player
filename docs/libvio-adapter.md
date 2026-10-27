# Built-in Libvio direct playback

Libvio catalogue browsing, search, episode details and direct playback are owned by
`libraries/libvio-core`. Android's `LibvioSpider` translates the shared models to
CatVod JSON; desktop consumes the shared catalogue executor directly. Neither path
needs a downloaded provider JAR, DEX conversion, ARM emulation or provider approval
for Libvio.

Existing type-3 `csp_Libvio` and `csp_LibvioGuard` sources select this adapter by
default. New configurations can use `builtin_libvio`. Source keys, names, visibility
and search settings are preserved. Desktop also keeps configured category ordering.
A similarly named provider, such as `csp_LibvioOther`, retains its existing runtime.
The optional `ext.siteUrl` selects a supported Libvio HTTPS origin; the default is
`https://www.libvio.pw/`.

The adapter reads home/category cards and both current and older episode markup.
Search uses the site's JSON suggestion API: the HTML search route may return
unfiltered cards even for a nonexistent title. Direct media URLs are read from the
page's player JSON, including percent/Base64 encodings and current direct URLs
labelled `encrypt: 3`. It does not execute page scripts or sniff embedded browsers.
It excludes cloud-drive/download lines. A title containing only cloud links reports
that direct playback is unavailable.

The site session supports its published automatic SHA-256 proof-of-work page,
with strict difficulty, iteration and elapsed-time limits and one retry. Interactive
verification and unsupported challenges remain explicit failures. Each source has
its own bounded cookie jar. Redirects remain within supported Libvio origins, page
bodies are bounded, and cancellation closes pending requests. Site cookies are not
included in the headers returned to the media player. Android supplies its existing
HTTP client configuration; desktop uses the direct route already verified for
Libvio. Nothing changes global proxy, account or cookie settings.

## Verification

Run offline fixtures and platform routing checks:

```sh
./gradlew-safe :libraries:libvio-core:test
./gradlew-safe :desktop:test --tests '*DesktopTvBoxConfigurationLoaderTest'
./gradlew-safe -PwumingEphemeralSigning=true :app:testMobileArm64_v8aVerificationUnitTest --tests '*BaseLoaderBuiltinTest'
./gradlew-safe -PwumingEphemeralSigning=true :app:testLeanbackArm64_v8aVerificationUnitTest --tests '*BaseLoaderBuiltinTest'
```

The core tests exercise parser variants, empty/error searches, poster sources,
category order and pagination, legacy saved links, URL encodings and signed queries,
JSON strings with braces, cloud-only titles, Android JSON compatibility, bounded
challenge handling, real loopback HTTP redirects, cookies, body limits and socket
cancellation. Desktop tests cover all three API aliases without a provider artifact
and confirm unrelated providers keep their configured runtime.

For an explicitly requested live check:

```sh
scripts/desktop/run-tvbox-desktop.sh --test-libvio
```

This checks home items/posters, categories and pagination, a missing-title search,
a real search, episode details and direct URL resolution. It prints a JSON result
with playback URL and headers; it does not establish media rendering by itself.
A separate bounded player check must confirm video and audio. Android compilation
and JVM tests do not establish playback on an Android device.
