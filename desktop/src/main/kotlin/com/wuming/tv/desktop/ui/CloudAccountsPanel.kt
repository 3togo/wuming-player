package com.wuming.tv.desktop.ui

import com.wuming.tv.core.cloud.CloudAccountProvider
import com.wuming.tv.core.cloud.OpenDriveLoginChallenge
import com.wuming.tv.core.cloud.OpenDriveLoginState
import com.wuming.tv.desktop.cloud.DesktopCloudAccounts
import com.wuming.tv.desktop.cloud.DesktopCredentialBackup
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import java.util.Arrays
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingWorker
import javax.swing.Timer

class CloudAccountsPanel(
    private val accounts: DesktopCloudAccounts = DesktopCloudAccounts(),
    private val playback: com.wuming.tv.desktop.cloud.DesktopCloudPlaybackResolver? = null,
) : JPanel(BorderLayout(10, 10)), AutoCloseable {
    private val logger = Logger.getLogger("tvbox.ui.cloud")
    private val model = DefaultListModel<CloudAccountProvider>()
    private val providers = JList(model)
    private val qr = JLabel("Select an account and choose Login.", SwingConstants.CENTER)
    private val status = JLabel()
    private val login = JButton("Login")
    private val open = JButton("Open QR link")
    private val verify = JButton("Verify")
    private val logout = JButton("Logout")
    private val savedCopies = JButton("Saved copies…")
    private val export = JButton("Export…")
    private val importButton = JButton("Import…")
    private val authentication = ConcurrentHashMap<String, Boolean>()
    private var challenge: OpenDriveLoginChallenge? = null
    private var pollTimer: Timer? = null
    private var pollBusy = false
    private var generation = 0

    init {
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        accounts.providers.forEach(model::addElement)
        providers.selectionMode = ListSelectionModel.SINGLE_SELECTION
        providers.cellRenderer = AccountRenderer()
        providers.preferredSize = Dimension(250, 360)
        providers.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }
        if (!model.isEmpty) providers.selectedIndex = 0

        qr.verticalAlignment = SwingConstants.CENTER
        qr.horizontalAlignment = SwingConstants.CENTER
        add(JLabel(
            if (accounts.credentials.persistenceAvailable) {
                "Account login state is stored securely in this user's Linux Secret Service."
            } else {
                "Install libsecret-tools to enable durable account login for this user."
            },
        ), BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(providers), JPanel(BorderLayout()).apply {
            add(qr, BorderLayout.CENTER)
        }).apply {
            resizeWeight = 0.28
            dividerLocation = 270
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(8, 0)).apply {
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(login)
                add(open)
                add(verify)
                add(logout)
                add(savedCopies)
                add(export)
                add(importButton)
            }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)

        login.addActionListener { beginLogin() }
        open.addActionListener { openChallenge() }
        verify.addActionListener { verify() }
        logout.addActionListener { logout() }
        savedCopies.addActionListener { playback?.let { SavedCopiesDialog.show(this, it) } }
        export.addActionListener { doExport() }
        importButton.addActionListener { doImport() }
        updateActions()
        loadAuthenticationState()
        DesktopCredentialBackup.migrate { file, error ->
            logger.log(Level.FINE, "Skipped legacy credential backup $file", error)
        }
    }

    private fun selected(): CloudAccountProvider? = providers.selectedValue

    private fun selectionChanged() {
        stopPolling()
        qr.icon = null
        qr.text = selected()?.let { provider ->
            if (authenticated(provider)) "${provider.displayName()} is signed in."
            else "Choose Login to create a ${provider.displayName()} QR code."
        }.orEmpty()
        status.text = ""
        updateActions()
    }

    private fun beginLogin() {
        val provider = selected() ?: return
        logger.info("login begin provider=${provider.id()}")
        stopPolling()
        busy("Creating ${provider.displayName()} login QR…")
        val current = ++generation
        object : SwingWorker<OpenDriveLoginChallenge, Void>() {
            override fun doInBackground() = provider.beginLogin()
            override fun done() {
                if (current != generation) return
                runCatching { get() }.onSuccess { value ->
                    logger.info(
                        "login challenge created provider=${provider.id()} contentLength=${value.content.length} " +
                            "expiresAt=${value.expiresAtEpochMillis}",
                    )
                    challenge = value
                    qr.text = ""
                    qr.icon = ImageIcon(CloudQrImage.render(value.content))
                    status.text = "Scan with the ${provider.displayName()} mobile app, then confirm login."
                    startPolling(current, provider, value)
                }.onFailure { failed("Could not start login", it) }
                updateActions()
            }
        }.execute()
    }

    private fun startPolling(
        current: Int,
        provider: CloudAccountProvider,
        challenge: OpenDriveLoginChallenge,
    ) {
        pollTimer = Timer(1_500) {
            if (!pollBusy && current == generation) poll(current, provider, challenge)
        }.apply {
            initialDelay = 900
            start()
        }
    }

    private fun poll(current: Int, provider: CloudAccountProvider, challenge: OpenDriveLoginChallenge) {
        pollBusy = true
        object : SwingWorker<OpenDriveLoginState, Void>() {
            override fun doInBackground() = provider.pollLogin(challenge.id)
            override fun done() {
                pollBusy = false
                if (current != generation) return
                runCatching { get() }.onSuccess { state ->
                    logger.info("login poll provider=${provider.id()} state=$state")
                    when (state) {
                        OpenDriveLoginState.WAITING -> status.text = "Waiting for ${provider.displayName()} QR scan…"
                        OpenDriveLoginState.SCANNED -> status.text = "QR scanned; confirm login on your device."
                        OpenDriveLoginState.CONFIRMED -> {
                            stopPolling(invalidate = false)
                            authentication[provider.id()] = true
                            status.text = "${provider.displayName()} login confirmed."
                            qr.icon = null
                            qr.text = "Signed in to ${provider.displayName()}."
                            providers.repaint()
                        }
                        OpenDriveLoginState.EXPIRED -> failed("The QR code expired", null)
                        OpenDriveLoginState.FAILED -> failed("Account login failed", null)
                    }
                }.onFailure { failed("Cloud login polling failed", it) }
                updateActions()
            }
        }.execute()
    }

    private fun verify() {
        val provider = selected() ?: return
        stopPolling()
        busy("Verifying ${provider.displayName()} account…")
        object : SwingWorker<Boolean, Void>() {
            override fun doInBackground() = provider.verifyAuthentication()
            override fun done() {
                runCatching { get() }.onSuccess { valid ->
                    logger.info("login verify provider=${provider.id()} valid=$valid")
                    authentication[provider.id()] = valid
                    status.text = if (valid) "${provider.displayName()} account verified."
                    else "${provider.displayName()} credential is no longer valid; login again."
                    providers.repaint()
                }.onFailure { failed("Account verification failed", it) }
                updateActions()
            }
        }.execute()
    }

    private fun logout() {
        val provider = selected() ?: return
        if (JOptionPane.showConfirmDialog(
                this,
                "Remove the saved ${provider.displayName()} credential?",
                "Account logout",
                JOptionPane.OK_CANCEL_OPTION,
            ) != JOptionPane.OK_OPTION
        ) return
        stopPolling()
        runCatching { provider.logout() }
            .onSuccess {
                logger.info("login logout provider=${provider.id()}")
                authentication[provider.id()] = false
                status.text = "Signed out of ${provider.displayName()}."
                qr.icon = null
                qr.text = "Choose Login to create a new QR code."
                providers.repaint()
            }
            .onFailure { failed("Cloud logout failed", it) }
        updateActions()
    }

    private fun doExport() {
        val pass = promptPassphrase("Set a passphrase (min 8 characters) to encrypt the backup") ?: return
        try {
            busy("Exporting credentials…")
            val path = DesktopCredentialBackup.exportTo(accounts.credentials, pass)
            status.text = "Credentials exported to ${path.toAbsolutePath()}"
        } catch (error: Exception) {
            failed("Credential export failed", error)
        } finally {
            Arrays.fill(pass, '\u0000')
            updateActions()
        }
    }

    private fun doImport() {
        val chooser = JFileChooser(DesktopCredentialBackup.defaultDirectory().toFile()).apply {
            dialogTitle = "Import credential backup"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                "TVBox credential backups (*.tvbox-credentials.json)", "json",
            )
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile.toPath()
        val pass = promptPassphrase("Enter the backup passphrase") ?: return
        try {
            busy("Importing credentials…")
            val count = DesktopCredentialBackup.importFrom(accounts.credentials, file, pass)
            loadAuthenticationState("Imported $count desktop account${if (count == 1) "" else "s"} from ${file.fileName}.")
        } catch (error: Exception) {
            failed("Credential import failed", error)
        } finally {
            Arrays.fill(pass, '\u0000')
            updateActions()
        }
    }

    private fun promptPassphrase(message: String): CharArray? {
        val field = JPasswordField(20)
        val result = JOptionPane.showConfirmDialog(
            this, field, message, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
        )
        if (result != JOptionPane.OK_OPTION) return null
        val chars = field.password ?: CharArray(0)
        if (chars.size < 8) {
            failed("Passphrase must contain at least 8 characters", null)
            return null
        }
        return chars
    }

    private fun openChallenge() {
        val uri = challenge?.content?.let(::URI) ?: return
        runCatching { Desktop.getDesktop().browse(uri) }
            .onFailure { failed("Could not open the QR link", it) }
    }

    private fun busy(message: String) {
        status.text = message
        login.isEnabled = false
        open.isEnabled = false
        verify.isEnabled = false
        logout.isEnabled = false
        savedCopies.isEnabled = false
        export.isEnabled = false
        importButton.isEnabled = false
    }

    private fun failed(message: String, error: Throwable?) {
        if (error != null) logger.log(Level.WARNING, "$message: ${rootMessage(error)}", error)
        status.text = if (error == null) "$message." else "$message: ${rootMessage(error)}"
        stopPolling()
    }

    private fun updateActions() {
        val provider = selected()
        val known = provider != null && authentication.containsKey(provider.id())
        val authenticated = provider != null && authenticated(provider)
        login.isEnabled = known && accounts.credentials.persistenceAvailable &&
            provider.available() && provider.supportsQrLogin() && !authenticated
        open.isEnabled = challenge != null && Desktop.isDesktopSupported()
        verify.isEnabled = authenticated
        logout.isEnabled = authenticated
        savedCopies.isEnabled = playback != null && provider?.id() == "quark"
        export.isEnabled = true
        importButton.isEnabled = true
    }

    private fun loadAuthenticationState(statusAfterLoad: String = "") {
        status.text = "Loading cloud account state…"
        object : SwingWorker<Map<String, Boolean>, Void>() {
            override fun doInBackground(): Map<String, Boolean> = accounts.providers.associate {
                it.id() to runCatching(it::authenticated).getOrDefault(false)
            }

            override fun done() {
                runCatching { get() }.onSuccess(authentication::putAll)
                    .onFailure { logger.log(Level.WARNING, "Could not load cloud account state", it) }
                status.text = ""
                providers.repaint()
                selectionChanged()
                status.text = statusAfterLoad
            }
        }.execute()
    }

    private fun authenticated(provider: CloudAccountProvider): Boolean = authentication[provider.id()] == true

    private fun stopPolling(invalidate: Boolean = true) {
        pollTimer?.stop()
        pollTimer = null
        pollBusy = false
        challenge = null
        if (invalidate) generation++
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }

    override fun close() = stopPolling()

    private inner class AccountRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
        ): java.awt.Component {
            val provider = value as? CloudAccountProvider
            val state = when {
                provider == null -> ""
                !provider.available() -> "Unavailable"
                !authentication.containsKey(provider.id()) -> "Checking…"
                authenticated(provider) -> "Signed in"
                else -> "Signed out"
            }
            return super.getListCellRendererComponent(
                list,
                provider?.let { "${it.displayName()} — $state" }.orEmpty(),
                index,
                selected,
                focus,
            )
        }
    }
}
