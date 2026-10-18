package com.wuming.tv.core.cloud

import java.util.Collections
import java.util.LinkedHashMap

/** Provider registry, authentication gates and account/file workflows shared by
 * every host. Provider creation, credential persistence and diagnostics are ports. */
class CloudStorageEngine<P : CloudStorageProvider> @JvmOverloads constructor(
    providers: Collection<P>?,
    private val statuses: OpenDriveFailurePolicy.StatusPort = OpenDriveFailurePolicy.StatusPort { null },
    private val diagnostics: Diagnostics = object : Diagnostics {},
    private val expiredSession: (P) -> Exception = { IllegalStateException("Cloud account session expired") },
    private val listingStatuses: OpenDriveFailurePolicy.StatusPort = statuses,
) {
    interface Diagnostics {
        fun unavailable(provider: String, message: String) {}
        fun authExpired(provider: String) {}
        fun failed(provider: String, item: String, error: Exception) {}
        fun proxied(provider: String, item: String, kind: String) {}
    }

    private val registry: Map<String, P> = LinkedHashMap<String, P>().apply {
        providers?.forEach { provider ->
            require(validId(provider.id())) { "OpenDrive provider ID is invalid" }
            require(putIfAbsent(provider.id(), provider) == null) { "Duplicate OpenDrive provider: ${provider.id()}" }
        }
    }.let(Collections::unmodifiableMap)

    fun providers(): List<P> = Collections.unmodifiableList(ArrayList(registry.values))
    fun provider(id: String?): P = registry[id] ?: throw IllegalArgumentException("Unknown OpenDrive provider")
    fun requireAvailable(id: String?): P = provider(id).also {
        if (!it.available()) {
            diagnostics.unavailable(it.id(), it.availabilityMessage())
            throw UnsupportedOperationException(it.availabilityMessage())
        }
    }

    @Throws(Exception::class)
    fun list(providerId: String?, parentId: String?): List<OpenDriveItem> {
        val provider = requireAvailable(providerId)
        check(provider.authenticated()) { "Cloud account is not logged in" }
        return try {
            val items: List<OpenDriveItem>? = provider.list(normalizeRoot(parentId))
            immutableItems(items)
        } catch (error: Exception) {
            if (listingStatuses.read(error)?.http != 401) throw error
            diagnostics.authExpired(provider.id())
            throw expiredSession(provider)
        }
    }

    @Throws(Exception::class)
    fun resolve(providerId: String?, itemId: String): OpenDrivePlayback {
        val provider = requireAvailable(providerId)
        if (!provider.authenticated()) {
            diagnostics.authExpired(provider.id())
            throw OpenDriveFailure(provider.id(), OpenDriveFailure.Reason.AUTH_REQUIRED)
        }
        val playback = try { provider.resolve(itemId) } catch (error: Exception) {
            val described = OpenDriveFailurePolicy.describe(provider.id(), error, statuses)
            diagnostics.failed(provider.id(), itemId, described)
            throw described
        }
        if (playback.url.isNotBlank()) {
            val kind = CloudDriveDetector.detect("", playback.url, "")
            if (kind != CloudDriveProviderKind.NONE) diagnostics.proxied(provider.id(), itemId, kind.name)
        }
        return playback
    }

    fun persistHandle(providerId: String?, itemId: String): Boolean = requireAvailable(providerId).persistHandle(itemId)
    fun providerForShare(url: String): P = registry.values.firstOrNull { it.available() && it.supportsShare(url) }
        ?: throw IllegalArgumentException("Unsupported cloud share link")

    @Throws(Exception::class)
    fun listShare(providerId: String?, url: String): List<OpenDriveItem> {
        val provider = requireAvailable(providerId)
        require(provider.supportsShare(url)) { "Unsupported cloud share link" }
        return immutableItems(provider.listShare(url))
    }

    @Throws(Exception::class)
    fun beginLogin(providerId: String?): OpenDriveLoginChallenge = requireAvailable(providerId).beginLogin()

    @Throws(Exception::class)
    fun pollLogin(providerId: String?, sessionId: String?): OpenDriveLoginState {
        require(!sessionId.isNullOrBlank()) { "Login session ID is required" }
        val state: OpenDriveLoginState? = requireAvailable(providerId).pollLogin(sessionId)
        return state ?: OpenDriveLoginState.FAILED
    }

    @Throws(Exception::class)
    fun logout(providerId: String?) = requireAvailable(providerId).logout()

    @Throws(Exception::class)
    fun <T> proxy(params: Map<String, String>?, dispatch: (P, Map<String, String>) -> T): T? {
        val id = params?.get("provider")?.takeIf { it.isNotBlank() } ?: return null
        val provider = requireAvailable(id)
        check(provider.authenticated()) { "Cloud account is not logged in" }
        return dispatch(provider, Collections.unmodifiableMap(LinkedHashMap(params)))
    }

    companion object {
        private val PROVIDER_ID = Regex("[a-z0-9][a-z0-9_-]{0,31}")
        private fun validId(id: String?): Boolean = id != null && PROVIDER_ID.matches(id)
        private fun normalizeRoot(parent: String?): String = if (parent.isNullOrBlank()) "root" else parent
        private fun immutableItems(items: List<OpenDriveItem>?): List<OpenDriveItem> =
            if (items == null) emptyList() else Collections.unmodifiableList(ArrayList(items))
    }
}
