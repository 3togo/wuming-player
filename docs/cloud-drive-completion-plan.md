# Cloud-drive improvement batch

## Scope and acceptance criteria

Complete the remaining improvements identified in the cloud-drive test review in one
working-tree batch. Earlier safe diagnostics, structured failures, fallback notices,
and explicit cleanup outcomes remain part of the final result.

1. **Shared saved-copy rules.** Extract provider/account/source ownership and lease
   eligibility into cloud-core; migrate Ali's tracked-original selection and expiry
   checks first. Keep provider-specific verification and delete APIs in their adapters.
2. **Playback protection across Ali, UC, and Quark.** Add process-wide opaque copy leases
   with bounded startup holds and reference-counted active users. Carry only the opaque
   token in internal playback metadata. Pin when the player starts, release on stop,
   replacement, and destruction. All three providers and manual cleanup must respect
   active leases. Deletion and pinning must not race; failures retain tracking records.
   Preserve UC same-source reuse and previous-source handoff, Quark original/smart reuse,
   and Ali source/account isolation. No global recycle-bin cleanup or new API fallback.
3. **On-demand stream check.** Add a Check stream action to the existing playback-info
   dialog on phone and TV. Check the current resolved resource without resolving another
   route or creating cloud copies. Bound redirects, nesting, request count, bytes, and
   time; inspect progressive bytes or one actual HLS segment. Keep headers internal to
   the request, strip app hints, and avoid forwarding credentials across origins.
   Cancel on dialog dismissal. Report reachability separately from decoded playback.
4. **Validation and delivery.** Add offline lifecycle, concurrency, probe, and transport
   regression tests. Run cloud/playback-core and Android focused tests, mobile and
   leanback APK builds, and the leanback remote checker. Document results and limits.

No device connection, installation, live-account fixture mutation, or new scheduled
work is included. Local builds and offline probes are not device playback verification.

## Completion record

Completed in this working-tree batch:

- [x] Shared ownership/expiry policy; Ali source reuse serialized across adapter instances.
- [x] Shared handoff/active-user leases across Ali, UC, Quark, player startup/stop, and manual cleanup.
- [x] Deletion reservations and failed-deletion tracking preservation.
- [x] Quark saved-source/account state reset on credential restoration.
- [x] On-demand stream check in playback info; explicit TV Stream tools control.
- [x] Cancellation, HLS segment checks, text-error rejection, size/time/request limits,
      cross-origin credential stripping, and internal metadata removal.
- [x] English, Simplified Chinese, and Traditional Chinese messages.
- [x] 272 offline tests passed: 220 Android cloud tests, 10 cloud-core tests,
      and 42 playback-core tests; no failures, errors, or skips.
- [x] Mobile arm64 debug and leanback arm64 debug APK builds passed offline.
- [x] Leanback checker passed: 129 effective layouts, 287 explicit focus targets,
      26 action contracts, and three application-label locales.
- [x] `git diff --check` passed.

No device or live-account testing was performed, and no APK was installed. Active-use
leases are process-local; external cast clients and ownership migration across rotated
cookie snapshots remain outside this batch. The stream check establishes bounded media
reachability, not decoded frames or sustained playback. See
[production diagnostics](cloud-drive-production-diagnostics.md) for behavior and limits.
