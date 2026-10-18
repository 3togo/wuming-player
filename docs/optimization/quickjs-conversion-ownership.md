# QuickJS conversion ownership follow-up

Date: 2026-09-11. Review found that `JSUtil.toArray` (strings and bytes) and
`toObject` allocated a native-backed wrapper before populating it, but did not
release it if a write or source collection access threw. The caller could not
release that partial result because it had never received it. Repeated failed
conversions could therefore retain wrappers until later runtime cleanup.

## Change and contracts

Conversion helpers now own the newly created wrapper until successful return.
On RuntimeException or Error they release it once, on the same calling thread,
and rethrow the original failure. A cleanup failure is suppressed rather than
replacing the conversion failure. Successful and empty conversions still return
an unreleased wrapper for the caller to own. Creation failures do not enter the
helper because no wrapper has been acquired yet.

No executor, context lifecycle, provider representation or JNI boundary changes.
Byte values remain signed Java integers in ordinary JS arrays; string/map order,
nulls and property writes retain their previous behavior. There is no throughput
claim or claim that host proxies measure native memory reclaimed.

## Validation

Ten QuickJS JVM tests passed: five existing timer/response ownership tests and
five new conversion tests covering signed values, order/nulls, empty inputs,
partial failures in all three conversions, owner thread, exact release counts
and cleanup-exception preservation. Leanback-lite ARMv7 app compilation passed.

An Android regression was added to `QuickJsResourcesInstrumentedTest`: perform
100 partial string-array conversions on the context owner executor and verify
wrapper records do not grow. Mobile and leanback ARM64 instrumentation compilation
passed; these tests were compiled, not executed. It complements existing HTTP array, promise and
timer ownership tests. Native-runtime execution remains pending authorization;
JVM proxies cannot establish actual native allocator behavior.

## Decision

Retain this managed ownership fix. Do not replace per-byte calls with typed arrays
without provider-contract coverage and actual runtime measurements. QuickJS is
already native-backed; no evidence here justifies another native implementation.
Remaining profiling: context/module creation, large response conversion counts,
JSON/base64 cost, native heap and teardown latency on an authorized Android device.

## Nested response and returned-object follow-up

The review continued into response construction and async results. `Connect.error`
now releases its parent if header creation or any response property write fails;
header wrappers still release after assignment. `Connect.success` also releases
the parent on an Error before rethrowing it, while ordinary Exceptions retain the
existing error-response fallback. Cleanup exceptions during parent release do not
hide the original failure.

`Async` now covers the `then` lookup itself with returned-object cleanup. A throwing
lookup previously bypassed the promise release. Plain objects still transfer to
the future on successful completion; promise-like results release after callback
registration. All work remains on the existing calling/owner thread.

Added three response tests (successful ownership, header-allocation failure, all
property-write failures) and two async tests (throwing lookup and plain-object
ownership). These verify wrapper release accounting with JVM proxies; they do not
measure native memory or runtime speed. No C/C++/Rust conversion is justified by
these resource-lifetime findings.

Follow-up validation: all 15 QuickJS JVM tests passed. Leanback-lite ARMv7 app
compilation and mobile/leanback ARM64 instrumentation compilation passed.
Instrumentation was compiled, not executed; no device was accessed.
