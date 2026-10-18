# Guarded provider read-only recovery (2026-10-01)

On the explicitly authorized Android 14 ARM64 device `192.0.2.10:43263`,
the first source `免费分享` from `https://szyyds.cn/tv/x.json` selected
`csp_DouDouGuard` but displayed no categories or content. Its native initializer
extracted `cache/sharedb/config.db` with writable permissions. Android rejected
that generated DEX, and the existing `Init.loader()` guard refused the null loader.

Changing permissions alone did not recover initialization. A fresh extraction
produced the same verified payload and repeated the error. The host now recovers
only this measured binary pair after the null-loader failure:

- Provider SHA-256: `b2755622dc43d9181b9d1a4bd9e11251fea15048fa2f82b91adafc57f1771551`.
- Generated payload SHA-256: `4b8ec2c46d0790d0737e6a2e30a217837d49e319a28b2ef819a1547dd7e0244e`.

`GuardedProviderRecovery` verifies both hashes, makes the generated payload
read-only, creates the nested loader, connects the provider's native bridge,
and runs `InitOrigin.init`. The exact pinned outer binary's loader field is
installed for this initialization and rolled back if it fails. Unknown binaries,
changed payloads, missing artifacts, and unrelated initialization exceptions
retain the existing failure guard. No app database or credential files are
repaired, deleted, or made available to the provider by this change. No binary or
decrypted payload is committed.

## Validation

- Loader, decoder, and recovery rejection JVM tests passed.
- Mobile ARM64 verification app, runner, and normal debug app built successfully.
- The opt-in native loader test with `native_loader_probe=true` and
  `native_loader_recovery=true` passed 1/1 in 0.814 seconds, runner code -1.
  Its trace recorded writable-DEX rejection followed by a valid nested loader,
  successful DouDouGuard initialization, and valid home JSON.
- Updating the normal app with `adb install -r` preserved its data. The first
  `免费分享` source then displayed category tabs, titles, and posters, and the
  provider trace recorded successful verified recovery.
- Temporary stay-awake settings were restored to the original value `15`.

The normal APK also includes the earlier comment-prefixed configuration decoder
fix. This validates home listing, not playback or all of the provider's hot tabs;
the separate DouDou endpoint/parser errors remain outside this loader repair.

The previous generated artifact was retained on the device as
`cache/sharedb/config.db.pre-repair-20261001` during diagnosis. A future provider
version must be investigated before adding new fingerprints; do not remove the
fingerprint checks or the null-loader guard to accept arbitrary extracted code.
