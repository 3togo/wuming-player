# Cloud core

`cloud-core` owns provider-neutral cloud-storage contracts, immutable file/playback/login models and
account-state policy. It does not own provider HTTP implementations, secrets, Android credential
storage, CatVod payloads, local proxy responses or login UI.

Desktop and Android hosts provide their own `CloudStorageProvider` implementations and compose
them through platform-specific registries.
