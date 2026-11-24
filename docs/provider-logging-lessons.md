# Provider responses containing percent escapes

AI剧 returned categories and videos, but opening its catalogue did nothing. Its
image URLs contained percent escapes such as `%2F` and `%3D`. `SpiderDebug.log`
passed the response to `String.format` without arguments, which threw
`UnknownFormatConversionException` before `SiteApi` could parse the videos.

The fix preserves the message literally when no formatting arguments are supplied.
Calls that supply arguments retain their existing formatting. This also protects
raw category, detail and search responses that use the same logger.

## Investigation lessons

- Filter logcat by the application and provider processes. Launcher image errors
  and runtime profile warnings do not establish a provider failure.
- A large JSON entry may be truncated by logcat. Missing fields at the end of a
  log entry do not prove that the original response was incomplete.
- Separate fetching, logging, parsing and rendering. A diagnostic statement can
  throw after a successful fetch and prevent otherwise valid data from loading.
- Background futures can retain exceptions without an obvious fatal log entry.
  A narrow caught-exception breakpoint exposed the failing logger and its caller.
  Keep debugger pauses short, then resume, detach and remove temporary forwards.
- Test both literal text and intentional formatting. Check preserved output, not
  only absence of an exception. An opt-in staged-provider test should exercise
  the actual `SiteApi` entry point as well as the synthetic regression.
- Confirm the installed package and signing certificate before an update. Separate
  testing editions have separate account stores; an empty store in another edition
  is not evidence that the original app lost credentials. Never clear data or
  uninstall an existing app to bypass a certificate mismatch.
- Copying an encrypted account backup only stages it. Restoration requires the
  import workflow and its password. Stored account records and unchanged hashes
  establish persistence, not successful authentication with every provider.
- Preserve unrelated working changes. Validate the exact focused candidate in an
  isolated checkout and keep credentials, provider payloads and device logs out of
  repository history. Retain hashes and sanitized summaries instead.
- Carry the isolated test-signing option through acceptance commands too. A
  signing-configuration rejection or missing offline dependency is a prerequisite
  failure, not a failed source regression test.

## Validation

The source fix is `bec7d25f9e3c851a04609db2f97713bbadb218b9`. On Ubuntu 26.10
x86-64 with OpenJDK 25 and Gradle 9.7.1, the public acceptance baseline passed:
8,751 JUnit cases, including 18 skips, and 227 Python tests. Separate artifact
audits ran 11 and 4 tests. Skips cover optional fixtures, hardware and desktop
display/runtime checks; those paths are not established by this run.

The ARM32 verification app and test runner built successfully. Both instrumentation
tests passed on Android 11: literal/formatted logging and the staged provider's
nonempty categories and videos through `SiteApi`. The updated application also
opened the AI漫剧 and AI真人剧 categories and rendered recommendation cards.
Video playback, other Android versions and desktop runtime behavior were not
tested in this investigation.
