# Automatic recovery from a stale native provider extraction

When the downloaded provider cannot initialize its nested class loader, it returns
no browse data and the TV's 点播 button appears unresponsive. Moving aside its
stale generated `code_cache/sharedb/config.jar` restored initialization and browsing
on Android 10 / ARM32. This establishes the stale file as the trigger, but does not
establish why the external provider originally left it behind.

The loader now retries initialization once after quarantining that exact measured
extraction. Recovery is entered only after the existing `Init.loader()` null check
fails. Both the provider and extraction must match the recorded SHA-256 values:

| Artifact | SHA-256 |
| --- | --- |
| Provider JAR | `9f6bdde0b66c9e4094e69dcba7502d59e5051edc09dc802b0c810b11557e16b5` |
| Stale generated extraction | `187998a5bb3c00016fae00c3762749228d8d2cc406ed0812ece34e0d35a3c480` |

The file is atomically moved to `config.jar.loader-recovery-backup` in the same
directory. One identical backup can be reused across process restarts; unrelated
backup contents and redirected files/directories prevent recovery. There is at
most one retry per process. The retry must pass the same initialization and
null-loader checks as an ordinary load before any spider or proxy is published.
If it fails, the original remains backed up and the existing crash guard rejects
the provider. A replacement produced during the failed retry is retained.

Application databases, accounts, preferences, downloaded provider JARs, and other
cache files are outside this repair. Unknown provider versions or changed payloads
still require investigation. The separate verified Android 14 read-only payload
recovery remains available under its original fingerprints.

This belongs in the Android adapter because it repairs a native provider's
Android-specific extraction path and initialization contract. Desktop does not
load this Android extraction.
