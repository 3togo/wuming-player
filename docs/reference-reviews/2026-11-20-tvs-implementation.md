# TVs reference adaptations — 2026-11-20 UTC

See the [incremental review](2026-11-20-tvs-incremental.md) for checkout
revisions and coverage. The scan boundary remains the saved tips in
[status.json](status.json).

- **JavaScript catalogue callbacks.** TVboxOSC's `b054127` changes
  `runtime/quickjs/src/main/assets/js/lib/spider.js` to accommodate both modern
  and legacy callbacks. Wuming's Graal adapter independently implements the
  same selection rule for its six catalogue operations. Modern methods take
  precedence when callable, with fallback per method. Modern detail and
  playback receive JavaScript arrays, and legacy calls retain their previous
  arguments. Calls preserve the source object as the receiver. The existing
  promise handling and response codec handle object and JSON-string results.
  The public Android JS loader uses its provider-supplied Spider path. This
  adaptation covers desktop catalogue callbacks and does not implement the
  QuickJS Spider class SDK or extend Android bundle behavior.
- **Curated provider review.** TVboxOSC's `746a25a` removed a risky JAR and
  updated its curated catalogue. Wuming's maintenance guide now describes
  reviewing changed executable provider references and removing confirmed
  unsafe artifacts from distribution. The process uses the existing depot
  validator for reachability and sampled content. No particular Wuming
  artifact was identified for removal in the reference review.
- **Optional native player recovery.** TVboxOSC's `600f5f3` and `b054127`
  address availability checks around installation of its Android mpv/libass
  module. Wuming's current mobile and leanback player factory builds Media3
  directly, and desktop launches a system player. The reviewed module
  installation and ASS fallback lifecycle has no equivalent entry point
  here. No player changes were made.

The JS-runtime suite passed 12 tests, including modern named exports, a mixed
default object, and existing legacy controls. Desktop Kotlin compilation and
generic-library, desktop and code-lifecycle boundary checks passed. The depot
validator's help command and the diff whitespace check passed. No live provider
or device was exercised. The earlier scan's pending deeper review of Python,
networking, subscriptions and provider fixtures remains recorded.
