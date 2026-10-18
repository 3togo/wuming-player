# Relocating protected spider toast messages

## Problem

The message `饭：已过滤视频中广告(14条)` appeared as an Android system toast at the top of the screen. Moving the app's normal notification view did not affect it.

## Root cause

The message is emitted by protected spider code after the spider JAR is loaded. At runtime that code calls `Toast.show()`, which reaches the process-wide hidden `INotificationManager.enqueueTextToast` service.

This explains why the first approaches did not work:

- Searching or rewriting the JAR could not find a stable plain `Toast` reference because the relevant class is protected and loaded at runtime.
- Changing `Notify` or the existing `PopupToast` only affects messages created through those app-owned paths.
- Wrapping `WindowManager.addView` is too late for a text toast because Android sends it to the notification service.

## Working approach

Install a process-wide proxy for `Toast.sService` during `Application.onCreate()`. Text toasts created anywhere in the app process, including provider and spider login code, are not enqueued as system toasts; their text is forwarded through `Notify` to `PopupToast`. The shared popup attaches to the current activity's decor view, anchors itself at the lower edge, and remains 24 dp above system bars or the on-screen keyboard. A custom toast whose text cannot be safely extracted is delegated unchanged instead of being silently discarded.

The popup uses the same semantic surface, text, outline, and corner treatment as the app's dialogs. App-owned validation and status messages must call `Notify.show(...)` instead of creating native Android toasts, so mobile and TV render the same non-interactive notification component.

Android blocks ordinary reflection of these hidden APIs for the app's target SDK, so the interceptor uses HiddenApiBypass for the narrowly scoped `Toast` and `INotificationManager` members.

## Android 15 trap

On the tested Android 15 device, `enqueueTextToast` returns primitive `boolean`. Returning `null` from the proxy causes an unboxing `NullPointerException` in `Toast.show()`. A swallowed matching call must return `true` when the reflected method's return type is `boolean`; older platform signatures can still return `null`.

## Verification

Verify on a device, not only with a successful build:

1. Clear logcat, force-stop the app, and launch it.
2. Reproduce video ad filtering.
3. Confirm `SystemToastInterceptor` logs `Redirected: 饭：已过滤视频中广告(...)`.
4. Confirm there is no `AndroidRuntime` crash and the app process remains alive.
5. Visually confirm the replacement popup is rendered at the bottom.

The intercepted text may be emitted more than once by the spider. `PopupToast.show()` dismisses the previous view before displaying the latest one, preventing stacked duplicates.

## Custom-toast path and Android version differences

Protected spider code can use either of Android's toast service methods:

- `enqueueTextToast` carries the message as a `CharSequence`.
- `enqueueToast` carries a `Toast.TN` callback instead of a direct message.

Intercepting only `enqueueTextToast` is insufficient. On the custom-toast path, inspect
`Toast.TN.mNextView`, recursively find its `TextView`, and match the extracted text before
suppressing the system toast.

On newer Android versions, `mNextView` can be stored as a
`WeakReference<View>` rather than a direct `View`. The extractor must unwrap
`java.lang.ref.Reference` values before traversing the view hierarchy. If it does not,
the system toast may be suppressed without displaying the replacement popup.

`ToastRelocationInstrumentedTest` protects this behavior by emitting the exact ad-filter
message through the custom-toast API and asserting that:

1. the message is redirected;
2. the replacement view contains the original text; and
3. the replacement view is positioned near the bottom of the activity.
