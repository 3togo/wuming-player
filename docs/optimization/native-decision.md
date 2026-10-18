# Native conversion decision and remaining evidence

Date: 2026-09-11. Decision: **defer native additions**. No measured remaining
end-to-end bottleneck currently qualifies for a C/C++/Rust prototype. This is a
conditional decision, not completion of the outstanding device release gates.

| Module | Decision | Evidence / next gate |
| --- | --- | --- |
| EPG | Keep streaming Java; Rust remains conditional | 108 equivalent host imports; verify real feeds and Android memory/time before reconsidering |
| Casting | Keep bounded managed staging and coordination | Matching staging/scheduling fixtures; Android remux, renderer compatibility, process memory and cancellation remain unmeasured |
| Search | Keep indexed/prepared Kotlin | 72 equivalent host samples, lower allocations; measure UI-visible latency before proposing native matching |
| Range | Keep managed transport; retain lazy signals | 32 matching synthetic samples show reduced allocation, no reliable speed gain; network/provider waits remain a separate question |
| Danmaku | Defer parser/rendering changes | Source review identifies bounded input but potentially large parsed object retention; needs separate import and frame profiles |
| QuickJS | Keep existing native engine and managed ownership | Source review finds bridge batching candidates, but no native-runtime workload timing or contract-preserving alternative measured |
| Providers/UI/domain policy | Keep managed | No qualifying computational bottleneck demonstrated |

## Danmaku review

`player/danmaku/Loader.java` enforces an 8 MiB input bound, copies the completed
ByteArrayOutputStream into an exact-size array, then gives the parser a source
independent of the already-closed HTTP response. Returning a view of the backing
buffer would remove that last copy but could retain spare capacity throughout
parsing. It is not adopted without measuring peak and retained memory together.

`Parser.java` reads lines, matches XML/text records, creates Android rendering
objects and retains comments ordered by time. Input bytes do not bound object count
or text-layout cost tightly. A loading window or comment limit changes seek and
synchronization behavior; it requires explicit compatibility fixtures, not merely
a faster parser. Profile import allocations separately from text measurement,
layout, draw time and frame drops before choosing a native boundary.

## QuickJS review

`quickjs/crawler/Spider.java` initializes and owns its context through a per-spider
single-thread executor. Pending async work, JS values, global state and teardown
have ownership/thread requirements that a new bridge must preserve. The engine is
already native-backed; wholesale conversion offers no demonstrated benefit.

`quickjs/utils/JSUtil.java` converts bytes with per-element JS array writes. A bulk
buffer could reduce calls, but typed-array substitution can change provider-visible
array and signed-byte behavior. Measure real payload sizes, conversion counts,
context/module initialization and cleanup in the existing runtime before changing
this API. Stub-based JVM tests cannot establish those runtime costs.

## Continuation gates

Offline managed changes and their host evidence are available for review. Remaining
Android work must use a device explicitly authorized for this optimization task:
the repository's AGENTS.md limits previous pairing permission to its original task.
No device was accessed. This restriction does not block additional offline fixtures.

For any future native proposal, first set an end-to-end improvement target and
compare against these optimized baselines, including JNI copying, cancellation,
process/native memory, ABI support, APK/startup impact and maintenance. Prefer a
bounded Rust data-processing prototype only if EPG/search remains CPU-bound; use
an established C/C++ media library only if casting evidence justifies integration.

## Danmaku follow-up

A narrower copy reduction is now implemented: transfer the private buffer only
when completely full, otherwise keep the exact-size copy. [60 host samples](danmaku-buffer-measurements.md)
show an 8 MiB allocation saving at the input limit and unchanged partial-buffer
allocation, with no extra retained payload capacity. This does not close the
parser/object-retention or frame-rendering profiling gates above.

### QuickJS conversion ownership follow-up

Fixed partial array/object conversion cleanup while preserving successful ownership,
signed bytes and caller-thread execution. Ten QuickJS JVM tests and ARMv7 app
compilation passed. [Details and remaining runtime gates](quickjs-conversion-ownership.md).
Added a repeated-failure Android wrapper regression; it has not been executed.
This is a resource-lifetime fix, not a measured bridge-throughput improvement.
