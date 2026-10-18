package com.wuming.android.tv.cloud.opendrive

import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.cloud.opendrive.OpenDriveRuntime.engine
import com.wuming.tv.core.cloud.*
import com.wuming.tv.core.cloud.OpenDriveAccountSnapshot
import com.wuming.tv.core.cloud.OpenDriveLoginChallenge
import com.wuming.tv.core.cloud.OpenDriveLoginState
import com.wuming.tv.core.cloud.OpenDriveTokenState
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single account authority for Cloud Storage UI and provider login sessions.
 *
 * Provider adapters still own the protocol secrets they need. This manager owns
 * the meaning of those tokens: presence is cheap and local, while VERIFIED
 * is granted only after a read-only first-party account request succeeds.
 */
class OpenDriveTokenManager(engine: OpenDriveEngine?) {
    private val engine: OpenDriveEngine
    private val verifications: ConcurrentHashMap<String?, Verification?> =
        ConcurrentHashMap<String?, Verification?>()
    private val loginSessions = ConcurrentHashMap<String?, String?>()
    private val providerLocks = ConcurrentHashMap<String?, Any>()

    init {
        requireNotNull(engine) { "OpenDrive engine is required" }
        this.engine = engine
    }

    fun accounts(): List<OpenDriveAccountSnapshot> {
        val result: MutableList<OpenDriveAccountSnapshot> = ArrayList<OpenDriveAccountSnapshot>()
        for (provider: OpenDriveProvider in engine.providers()) result.add(snapshot(provider.id()))
        return java.util.List.copyOf<OpenDriveAccountSnapshot>(result)
    }

    fun snapshot(providerId: String): OpenDriveAccountSnapshot {
        val provider: OpenDriveProvider = engine.provider(providerId)
        var available: Boolean
        var present: Boolean
        try {
            available = provider.available()
            present = available && provider.authenticated()
        } catch (error: RuntimeException) {
            AppLog.w("OpenDriveToken", "snapshot failed provider=$providerId error=${error.javaClass.simpleName}: ${error.message}")
            available = false
            present = false
        }
        val state: OpenDriveTokenState
        if (!available) {
            state = OpenDriveTokenState.UNAVAILABLE
        } else if (!present) {
            verifications.remove(providerId)
            state = OpenDriveTokenState.SIGNED_OUT
        } else {
            val verification: Verification? = verifications.get(providerId)
            state =
                (
                    if (verification == null ||
                        verification.expired()
                    ) {
                        OpenDriveTokenState.PRESENT
                    } else {
                        verification.state
                    }
                    )!!
        }
        return OpenDriveAccountSnapshot(
            provider.id(),
            provider.displayName(),
            available,
            provider.supportsQrLogin(),
            present,
            state
        )
    }

    /** Runs synchronously; callers must use a worker thread.  */
    fun verify(providerId: String): OpenDriveAccountSnapshot {
        synchronized(lock(providerId)) {
            val provider: OpenDriveProvider = engine.provider(providerId)
            val before: OpenDriveAccountSnapshot = snapshot(providerId)
            if (!before.availableAccessor() || !before.tokenPresentAccessor()) return before
            verifications.put(providerId, Verification.checking())
            try {
                val valid: Boolean = provider.verifyAuthentication()
                verifications.put(
                    providerId,
                    Verification.finished(
                        if (valid) OpenDriveTokenState.VERIFIED else OpenDriveTokenState.INVALID
                    )
                )
            } catch (error: java.io.IOException) {
                AppLog.w("OpenDriveToken", "verify network error provider=$providerId error=${error.javaClass.simpleName}: ${error.message}")
                verifications.put(providerId, Verification.finished(OpenDriveTokenState.ERROR))
            } catch (error: Exception) {
                AppLog.w("OpenDriveToken", "verify auth error provider=$providerId error=${error.javaClass.simpleName}: ${error.message}")
                verifications.put(providerId, Verification.finished(OpenDriveTokenState.INVALID))
            }
            return snapshot(providerId)
        }
    }

    /** Starts one authoritative QR session per provider.  */
    @Throws(Exception::class)
    fun beginLogin(providerId: String): OpenDriveLoginChallenge {
        synchronized(lock(providerId)) {
            val account: OpenDriveAccountSnapshot = snapshot(providerId)
            check(!account.verified()) { "Cloud account is already logged in" }
            if (account.stateAccessor() == OpenDriveTokenState.INVALID) {
                resetLocked(providerId)
            } else {
                check(!account.tokenPresentAccessor()) {
                    "Cloud token must be verified or reset before login"
                }
            }
            val challenge: OpenDriveLoginChallenge? = engine.beginLogin(providerId)
            check(
                !(
                    challenge == null || challenge.idAccessor()
                        .isBlank()
                    )
            ) { "Cloud provider returned an invalid login session" }
            loginSessions.put(providerId, challenge.idAccessor())
            verifications.remove(providerId)
            return challenge
        }
    }

    @Throws(Exception::class)
    fun pollLogin(providerId: String, sessionId: String?): OpenDriveLoginState {
        synchronized(lock(providerId)) {
            val active: String? = loginSessions.get(providerId)
            if (active == null || active != sessionId) return OpenDriveLoginState.EXPIRED
            val state: OpenDriveLoginState = engine.pollLogin(providerId, sessionId)
            if (state == OpenDriveLoginState.CONFIRMED) {
                loginSessions.remove(providerId, sessionId)
                val verified: OpenDriveAccountSnapshot = verify(providerId)
                return if (verified.verified()) OpenDriveLoginState.CONFIRMED else OpenDriveLoginState.FAILED
            }
            if (state == OpenDriveLoginState.EXPIRED || state == OpenDriveLoginState.FAILED) {
                loginSessions.remove(providerId, sessionId)
            }
            return state
        }
    }

    /** Clears protocol state, tokens, handles and the manager's cached verdict once.  */
    @Throws(Exception::class)
    fun reset(providerId: String) {
        synchronized(lock(providerId)) {
            resetLocked(providerId)
        }
    }

    @Throws(Exception::class)
    private fun resetLocked(providerId: String) {
        loginSessions.remove(providerId)
        verifications.remove(providerId)
        engine.logout(providerId)
    }

    /** Serialize restore with verification and QR polling, which may persist refreshed tokens.  */
    fun replaceCredentials(replacement: Runnable) {
        withProviderLocks(
            engine.providers(),
            0,
            Runnable {
                replacement.run()
                credentialsRestored()
            }
        )
    }

    private fun withProviderLocks(
        providers: List<OpenDriveProvider>,
        index: Int,
        action: Runnable
    ) {
        if (index == providers.size) {
            action.run()
            return
        }
        synchronized(lock(providers.get(index).id())) {
            withProviderLocks(providers, index + 1, action)
        }
    }

    /** Imported credentials must be checked again, even if the previous account was verified.  */
    fun credentialsRestored() {
        for (provider: OpenDriveProvider in engine.providers()) {
            synchronized(lock(provider.id())) {
                loginSessions.remove(provider.id())
                verifications.remove(provider.id())
                try {
                    provider.credentialsRestored()
                } catch (error: RuntimeException) {
                    AppLog.w("OpenDriveToken", "credentialsRestored failed provider=${provider.id()} error=${error.javaClass.simpleName}: ${error.message}")
                }
            }
        }
    }

    fun invalidate(providerId: String) {
        verifications.remove(providerId)
    }

    private fun lock(providerId: String): Any {
        engine.provider(providerId)
        return providerLocks.computeIfAbsent(providerId) { ignored: String? -> Any() }
    }

    @JvmRecord
    private data class Verification(val state: OpenDriveTokenState?, val checkedAt: Long) {
        fun expired(): Boolean = state != OpenDriveTokenState.CHECKING &&
            System.currentTimeMillis() - checkedAt >= VERIFICATION_TTL_MILLIS

        companion object {
            @JvmStatic
            fun checking(): Verification =
                Verification(OpenDriveTokenState.CHECKING, System.currentTimeMillis())

            @JvmStatic
            fun finished(state: OpenDriveTokenState?): Verification =
                Verification(state, System.currentTimeMillis())
        }
    }

    private object Holder {
        val INSTANCE = OpenDriveTokenManager(engine())
    }

    companion object {
        private val VERIFICATION_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5)

        @JvmStatic
        fun get(): OpenDriveTokenManager = Holder.INSTANCE
    }
}
