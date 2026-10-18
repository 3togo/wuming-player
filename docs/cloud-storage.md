# Trusted cloud-storage cache management

TVBox keeps cloud cleanup behind a host-owned adapter boundary. Downloaded
spider JARs are content providers, not trusted storage adapters, and cannot
register a cloud deletion capability.

## Trust model

- A deletion adapter must be compiled and signed with TVBox.
- The host database stores opaque account, folder, and file IDs but never
  cookies, tokens, URLs, AppSecrets, or other credentials.
- Every remote file must be recorded before it becomes eligible for deletion.
- An adapter may return only IDs present in the active host-owned record set.
- Duplicate, unknown, or untracked IDs make the complete result fail closed.
- Each cleanup attempt creates an append-only audit event.
- Unsupported services open their official application for manual management.

The management page currently recognizes Aliyun Drive, Quark Drive, UC Drive,
Baidu Netdisk, 123 Netdisk, and 115. Listing a service and opening its official
application does not grant it deletion capability; every listed service remains
unsupported for direct cleanup until a reviewed adapter is compiled into TVBox.

QR authentication handoff is a separate feature with a different trust boundary.
See [Cloud-drive QR login handoff](cloud-login.md).

## Adding a provider

Implement `TrustedCloudAdapter` in
`app/src/main/java/com/wuming/android/tv/cloud/` and register it from
`TrustedCloudRegistry`. Do not expose registration to downloaded code,
configuration JSON, or a provider JAR.

This boundary prevents the host cleanup feature from delegating deletion to a
downloaded JAR. It does not sandbox arbitrary JAR code loaded elsewhere in the
same application process. Fully isolating untrusted providers requires removing
remote code loading or running it in a separately permissioned process.

An adapter receives an immutable list of `CloudCacheRecord` objects and returns
the exact remote file IDs it deleted:

```java
return new CloudStorageManager.Result(
        true,
        deletedIds.size(),
        freedBytes,
        "",
        deletedIds);
```

The host verifies every returned ID before changing local state. Adapters must
also verify the active account and the dedicated `TVBoxCache` folder using the
opaque IDs in each record.

## Recording a new cache file

After a trusted adapter creates a remote cache file, call:

```java
CloudStorageRepository.record(CloudCacheRecord.create(
        provider,
        accountHash,
        remoteFolderId,
        remoteFileId,
        displayName,
        sourceSiteKey,
        sizeBytes));
```

`accountHash` must be a stable one-way account fingerprint and must not contain
an email address, phone number, token, or raw provider user ID.

## Alibaba PDS

Alibaba Cloud's generic `aliyun-java-sdk-core` is not a Drive and Photo Service
file client. The official PDS Android SDK can list and delete files, but it
requires a PDS domain/API host and an access token issued by the integrator's
backend. It does not automatically reuse a consumer Aliyun Drive application's
login session. Add a PDS adapter only when that backend contract and credential
flow are available.
