package com.wuming.android.tv.ui.dialog

import com.wuming.android.tv.utils.TextScale

import android.content.ContentResolver
import android.content.ContentValues
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.api.Decoder
import com.wuming.android.tv.api.config.LiveConfig
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.api.config.WallConfig
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.databinding.DialogConfigBinding
import com.wuming.android.tv.event.ServerEvent
import com.wuming.android.tv.impl.ConfigCallback
import com.wuming.android.tv.server.RemoteAccess
import com.wuming.android.tv.server.Server
import com.wuming.android.tv.ui.custom.CustomTextListener
import com.wuming.android.tv.utils.FileChooser
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.PermissionUtil
import com.wuming.android.tv.utils.QRCode
import com.wuming.android.tv.utils.QrSizing
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.wuming.android.tv.utils.UrlUtil
import com.github.catvod.utils.Json
import com.github.catvod.utils.Path
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets

open class LeanbackConfigDialog(
    private val activity: FragmentActivity,
) : DialogInterface.OnDismissListener {
    companion object {
        private val PRETTY_GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        @JvmStatic fun create(activity: FragmentActivity): LeanbackConfigDialog = LeanbackConfigDialog(activity)
    }

    private var launcher: ActivityResultLauncher<Intent>? = null
    private val callback = activity as ConfigCallback
    private val binding =
        DialogConfigBinding.inflate(
            com.wuming.android.tv.utils.DialogUtil
                .inflater(activity),
        )
    private val dialog = TvAlertDialogBuilder(activity, R.style.DarkDialogStyle).setView(binding.root).create()
    private var append = true
    private var edit = false

    @Volatile private var currentJson: String? = null

    @Volatile private var currentJsonUrl: String? = null
    private var url: String? = null
    private var type = 0
    private var prefillUrl: String? = null
    private var adminUnlocked = false
    private var bundledUrlLocked = false

    open fun type(type: Int): LeanbackConfigDialog {
        this.type = type
        return this
    }

    /** Use a depot URL instead of the current configuration URL. */
    open fun url(url: String?): LeanbackConfigDialog {
        prefillUrl = url
        return this
    }

    open fun unlockedUrl(): LeanbackConfigDialog {
        adminUnlocked = true
        return this
    }

    open fun edit(): LeanbackConfigDialog {
        edit = true
        return this
    }

    open fun launcher(launcher: ActivityResultLauncher<Intent>?): LeanbackConfigDialog {
        this.launcher = launcher
        return this
    }

    open fun show() {
        initDialog()
        initView()
        initEvent()
    }

    private fun initDialog() {
        dialog.show()
        val window = dialog.window!!
        val params = window.attributes
        params.width = LayoutConfig.getScreenWidthPixels(LayoutConfig.TV_CONFIG_DIALOG_WIDTH_RATIO)
        window.attributes = params
        window.setDimAmount(0f)
        dialog.setOnDismissListener(this)
    }

    private fun initView() {
        RemoteAccess.openPairingWindow()
        applyResponsiveSizing()
        val qrAddress = Server.get().getAddress(3)
        binding.editName.setText(getConfig()!!.name)
        url = prefillUrl ?: getUrl()
        bundledUrlLocked = !adminUnlocked && BundledSourceAccess.isBundled(activity, url)
        binding.text.setText(if (bundledUrlLocked) "" else url)
        binding.text.visibility = if (bundledUrlLocked) View.GONE else View.VISIBLE
        binding.divider.visibility = if (bundledUrlLocked) View.GONE else View.VISIBLE
        if (!bundledUrlLocked) binding.text.setSelection(if (TextUtils.isEmpty(url)) 0 else url!!.length)
        binding.positive.setText(if (edit) R.string.dialog_edit else R.string.dialog_positive)
        binding.chooseHint.setText(getChooseHint())
        binding.code.setImageBitmap(QRCode.getBitmapPx(qrAddress, QrSizing.encodePx(), 0))
        binding.info.text = ResUtil.getString(R.string.push_info, Server.get().getAddress())
    }

    private fun applyResponsiveSizing() {
        val qrSize = QrSizing.clampPx(LayoutConfig.getTvConfigQrSizePixels())
        val contentHeight = LayoutConfig.getTvConfigContentHeightPixels()

        val outerPadding = LayoutConfig.getTvConfigOuterPaddingPixels()
        val innerPadding = LayoutConfig.getTvConfigInnerPaddingPixels()
        binding.root.setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
        setSize(binding.code, qrSize, qrSize)
        setHeight(binding.content, contentHeight)
        setStartMargin(binding.content, LayoutConfig.getTvConfigContentMarginPixels())
        binding.info.setPadding(innerPadding, 0, innerPadding, 0)
        binding.info.setLineSpacing(innerPadding.toFloat(), 1f)
        TextScale.bind(binding.info)
        binding.text.setPadding(innerPadding, 0, innerPadding, 0)
        TextScale.bind(binding.text)
        setBottomMargin(binding.text, LayoutConfig.getTvConfigDividerSizePixels())
        setSize(binding.divider, ViewGroup.LayoutParams.MATCH_PARENT, LayoutConfig.getTvConfigDividerSizePixels())
        setBottomMargin(binding.divider, LayoutConfig.getTvConfigDividerMarginPixels())
        binding.editName.setPadding(innerPadding, 0, innerPadding, 0)
        TextScale.bind(binding.editName)
        setBottomMargin(binding.editName, innerPadding)
        TextScale.bind(binding.chooseHint)
        setBottomMargin(binding.chooseHint, innerPadding)
        TextScale.bind(binding.help)
        TextScale.bind(binding.choose)
        TextScale.bind(binding.positive)
        TextScale.bind(binding.negative)
        setEndMargin(binding.help, outerPadding)
        setEndMargin(binding.choose, outerPadding)
        setEndMargin(binding.positive, outerPadding)
    }

    private fun getChooseHint(): Int =
        when (type) {
            1 -> R.string.dialog_config_choose_live_hint
            2 -> R.string.dialog_config_choose_wall_hint
            else -> R.string.dialog_config_choose_vod_hint
        }

    private fun setHeight(
        view: View,
        height: Int,
    ) {
        val params = view.layoutParams
        params.height = height
        view.layoutParams = params
    }

    private fun setSize(
        view: View,
        width: Int,
        height: Int,
    ) {
        val params = view.layoutParams
        params.width = width
        params.height = height
        view.layoutParams =
            params
    }

    private fun setStartMargin(
        view: View,
        margin: Int,
    ) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        params.marginStart =
            margin
        view.layoutParams = params
    }

    private fun setBottomMargin(
        view: View,
        margin: Int,
    ) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        params.bottomMargin =
            margin
        view.layoutParams = params
    }

    private fun setEndMargin(
        view: View,
        margin: Int,
    ) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        params.marginEnd =
            margin
        view.layoutParams = params
    }

    private fun getConfig(): Config? =
        when (type) {
            0 -> VodConfig.get().getConfig()
            1 -> LiveConfig.get().getConfig()
            2 -> WallConfig.get().getConfig()
            else -> null
        }

    private fun initEvent() {
        EventBus.getDefault().register(this)
        binding.help.setOnClickListener(this::onHelp)
        binding.choose.setOnClickListener(this::onChoose)
        binding.positive.setOnClickListener(this::onPositive)
        binding.negative.setOnClickListener(this::onNegative)
        binding.text.addTextChangedListener(
            object : CustomTextListener() {
                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    detect(s!!.toString())
                }
            },
        )
        binding.text.setOnEditorActionListener { _, actionId, _ ->
            if (actionId ==
                EditorInfo.IME_ACTION_DONE
            ) {
                binding.positive.performClick()
            }
            ; true
        }
    }

    private fun getUrl(): String =
        when (type) {
            0 -> VodConfig.getUrl()
            1 -> LiveConfig.getUrl()
            2 -> WallConfig.getUrl()
            else -> ""
        }

    private fun onChoose(view: View) {
        FileChooser.from(launcher).show(Path.root("Download/TV"))
        dialog.dismiss()
    }

    private fun onHelp(view: View) {
        val description =
            if (type ==
                0
            ) {
                R.string.dialog_config_help_vod
            } else if (type == 1) {
                R.string.dialog_config_help_live
            } else {
                R.string.dialog_config_help_wall
            }
        val current = getConfig()!!
        val hasJsonConfig = type != 2 && !TextUtils.isEmpty(current.url)
        val intro = ResUtil.getString(description)
        val message = if (hasJsonConfig) intro + "\n\n" + ResUtil.getString(R.string.dialog_config_loading_json) else intro
        val builder = TvAlertDialogBuilder(view.context, R.style.ConfigHelpDialogStyle)
        builder.setTitle(R.string.dialog_config_help_title)
        builder.setMessage(message)
        builder.setPositiveButton(R.string.dialog_positive, null)
        if (hasJsonConfig) builder.setNegativeButton(R.string.dialog_config_backup_current) { _, _ -> backupCurrentConfig() }
        val helpDialog = builder.create()
        helpDialog.show()
        val window = helpDialog.window!!
        val params = window.attributes
        params.width = LayoutConfig.getScreenWidthPixels(LayoutConfig.TV_CONFIG_HELP_DIALOG_WIDTH_RATIO)
        window.attributes = params
        val messageView = helpDialog.findViewById<TextView>(android.R.id.message)
        if (messageView != null) {
            messageView.setTextIsSelectable(true)
            messageView.typeface = Typeface.MONOSPACE
            TextScale.setRole(messageView, com.wuming.tv.core.design.TextRole.BODY)
            if (hasJsonConfig) loadJsonPreview(helpDialog, messageView, intro, current.url)
        }
    }

    private fun loadJsonPreview(
        helpDialog: AlertDialog,
        messageView: TextView,
        intro: String,
        sourceUrl: String,
    ) {
        Task.execute {
            val preview =
                try {
                    PRETTY_GSON.toJson(JsonParser.parseString(loadCurrentJson(sourceUrl)))
                } catch (
                    e: Exception,
                ) {
                    ResUtil.getString(R.string.dialog_config_preview_fail)
                }
            val content = intro + "\n\n" + preview
            App.post { if (helpDialog.isShowing) messageView.text = content }
        }
    }

    private fun backupCurrentConfig() {
        val current = getConfig()!!
        val sourceUrl = current.url
        val fileName = getBackupFileName(current)
        PermissionUtil.requestFile(activity) { granted ->
            if (!granted) {
                Notify.show(R.string.dialog_config_backup_fail)
            } else {
                Task.execute {
                    var success = false
                    try {
                        val json = loadCurrentJson(sourceUrl)
                        if (Json.isObj(json)) {
                            success = writeBackup(json, fileName)
                            if (success) removeLegacyExampleBackup()
                        }
                    } catch (
                        ignored: Exception,
                    ) {
                    }
                    val backupSuccess = success
                    App.post {
                        Notify.show(
                            if (backupSuccess) R.string.dialog_config_backup_success else R.string.dialog_config_backup_fail,
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    @Throws(Exception::class)
    private fun loadCurrentJson(sourceUrl: String): String {
        if (sourceUrl == currentJsonUrl && Json.isObj(currentJson)) return currentJson!!
        val json =
            if (sourceUrl.startsWith(
                    "file:",
                )
            ) {
                Path.read(Path.local(sourceUrl))
            } else {
                Decoder.getJsonObject(UrlUtil.convert(sourceUrl), "ConfigBackup")
            }
        if (!Json.isObj(json)) throw Exception("Current configuration is not a JSON object")
        currentJsonUrl = sourceUrl
        currentJson = json
        return json
    }

    private fun getBackupFileName(config: Config): String {
        var name =
            if (TextUtils.isEmpty(config.name)) {
                if (type ==
                    0
                ) {
                    "vod-config"
                } else {
                    "live-config"
                }
            } else {
                config.name.trim { it <= ' ' }
            }
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return name + ".json"
    }

    private fun writeBackup(
        json: String,
        fileName: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {
            val file = Path.root("Download/TV", fileName)
            Path.write(file, json.toByteArray(StandardCharsets.UTF_8))
            return Path.exists(file)
        }
        val resolver = activity.contentResolver
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TV")
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri, "wt").use { output ->
                if (output == null) throw IllegalStateException("Unable to open backup file")
                output.write(json.toByteArray(StandardCharsets.UTF_8))
                val ready = ContentValues()
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, ready, null, null)
                true
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun removeLegacyExampleBackup() {
        val legacy = Path.root("Download/TV", if (type == 0) "vod-config.json" else "live-config.json")
        if (!Path.exists(legacy)) return
        val content = Path.read(legacy)
        val generatedExample =
            if (type ==
                0
            ) {
                content.contains("https://example.com/spider.jar") && content.contains("csp_Demo")
            } else {
                content.contains("https://example.com/live.txt") &&
                    content.contains("\"name\": \"Demo\"")
            }
        if (generatedExample) Path.clear(legacy)
    }

    private fun detect(s: String) {
        if (append && s.equals("h", true)) {
            append = false
            binding.text.append("ttp://")
        } else if (append && s.equals("f", true)) {
            append = false
            binding.text.append("ile://")
        } else if (append && s.equals("a", true)) {
            append = false
            binding.text.append("ssets://")
        } else if (s.length > 1) {
            append = false
        } else if (s.isEmpty()) {
            append = true
        }
    }

    private fun onPositive(view: View) {
        val name =
            binding.editName.text
                .toString()
                .trim { it <= ' ' }
        val text = if (bundledUrlLocked) url.orEmpty() else
            binding.text.text.toString().trim { it <= ' ' }
        // Config reads and writes use the in-memory cache.
        if (edit) {
            Config
                .find(url, type)
                .url(text)
                .name(name)
                .update()
        }
        if (text.isEmpty()) Config.delete(url, type)
        if (name.isEmpty()) callback.setConfig(Config.find(text, type)) else callback.setConfig(Config.find(text, name, type))
        dialog.dismiss()
    }

    private fun onNegative(view: View) {
        dialog.dismiss()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    open fun onServerEvent(event: ServerEvent) {
        if (event.type() != ServerEvent.Type.SETTING) return
        if (bundledUrlLocked) return
        binding.editName.setText(event.name())
        binding.name.text = event.name()
        binding.text.setText(event.text())
        binding.text.setSelection(binding.text.text!!.length)
    }

    override fun onDismiss(dialogInterface: DialogInterface) {
        RemoteAccess.closePairingWindow()
        EventBus.getDefault().unregister(this)
    }
}
