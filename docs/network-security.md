# HTTPS certificate policy

The shared HTTP client has two certificate modes:

- **Platform validation** uses Android's normal certificate chain and hostname
  verification. This is the default for fresh installations.
- **Legacy HTTPS compatibility** accepts invalid and hostname-mismatched
  certificates. It exists only for user-configured providers that cannot be
  fixed at their origin.

Upgrades retain legacy compatibility during migration so an application update
does not unexpectedly disable existing provider sources. The setting is shown
on both mobile and Leanback. Users should turn it off after confirming their
configured sources work with normal HTTPS validation.

Changing the setting rebuilds the shared metadata and player HTTP clients and
clears their derived timeout-client cache. Plain HTTP remains available because
many local and user-configured media sources require it; this setting concerns
HTTPS certificate validation only.

The provider runtime is isolated in its own Android process. The selected policy
is also sent to that process explicitly, avoiding stale cross-process
`SharedPreferences` caches without restarting providers or interrupting active
requests.

## Regression guardrails

- Never silently retry a certificate failure with the legacy trust manager.
- Never use legacy TLS for a fresh installation by default.
- Preserve the migration marker and policy keys when clearing session settings.
- Rebuild every shared client after the policy changes.
- Do not log full provider URLs, authorization headers, cookies, or query
  parameters when reporting certificate failures.

Authenticated Quark API and streaming clients explicitly restore normal certificate and hostname
validation and use fresh connection pools, even when cloned from a shared legacy-compatible
client. Shared cookie, DNS and proxy settings are preserved. Other first-party cloud APIs also
use verified transports; legacy provider compatibility must not weaken account traffic.

## DNS selection and cached answers

Selecting **System** removes the previously selected DoH resolver. Every resolver selection
invalidates the app's DNS answers immediately; no app restart is needed for subsequent lookups.
`DnsResolverCache` in `source-core` owns this platform-neutral policy. It stores each answer and
its expiry together, uses monotonic elapsed time, bounds the cache, and discards results from
lookups that were in flight when the resolver changed or the cache was cleared. Failed or empty
answers are not cached. Host overrides and numeric IPv4/IPv6 addresses remain supported.

The Android `OkDns` port supplies System DNS and the selected DoH transport. The existing
Alibaba fallback for failed resolution is retained with numeric bootstrap addresses; DoH calls
now have bounded connection/read and overall request timeouts. Switching back to System does
not mean disabling that existing fallback, which is only attempted after a resolution failure.
These changes do not identify poisoned but syntactically valid public IP addresses, bypass
regional restrictions, or repair an unreachable media CDN. They invalidate the app cache;
platform DNS caches and already active HTTP connections retain their own lifetimes.

`DnsResolverCacheTest` exercises resolver changes, expiry, eviction, fallback, failed answers,
and concurrent invalidation. `OkDnsTest` verifies the Android adapter's System/DoH transitions,
invalid-selection handling, host overrides, numeric addresses, and international hostnames
without querying public DNS services.
