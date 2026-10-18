package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.App
import com.wuming.android.tv.Constant
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.api.projection.ProjectionManager
import com.wuming.android.tv.bean.CastVideo
import com.wuming.android.tv.bean.Device
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.databinding.DialogDeviceBinding
import com.wuming.android.tv.player.PlayerHelper
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.server.CastMediaAccess
import com.wuming.android.tv.server.RemoteAccess
import com.wuming.android.tv.server.Server
import com.wuming.android.tv.setting.DisplaySettings
import com.wuming.android.tv.ui.adapter.DeviceAdapter
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.ScanTask
import com.wuming.android.tv.utils.Task
import com.wuming.tv.core.playback.CastQueue
import com.github.catvod.utils.Path
import com.github.catvod.utils.Util
import com.google.android.material.bottomsheet.BottomSheetBehavior
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.Proxy
import java.util.concurrent.TimeUnit

open class CastDialogV2 :
    BaseDialog(),
    DeviceAdapter.OnClickListener,
    ScanTask.Listener,
    ProjectionManager.DiscoveryCallback {
    private var body: FormBody.Builder? = null
    private var client: OkHttpClient? = createControlClient()
    private var binding: DialogDeviceBinding? = null
    private var adapter: DeviceAdapter? = null
    private var scanTask: ScanTask? = ScanTask(this)
    private var onCastedCallback: Runnable? = null
    private var queue: CastQueue? = null
    private var video: CastVideo? = null
    private var fm = false
    private var historyData: History? = null
    private var player: PlayerManager? = null
    private val audioTrackNames = ArrayList<String>()
    private val audioTrackFormats = ArrayList<String?>()
    private var selectedAudioTrackIndex = 0

    private fun getBody(): FormBody.Builder {
        var currentBody = body
        if (currentBody == null) {
            currentBody = FormBody.Builder()
            body = currentBody
            currentBody.add("device", Device.get().toString())
            currentBody.add("config", VodConfig.get().config.toString())
            val history = historyData
            if (history != null) {
                val originalPic = history.vodPic
                var fd = originalPic
                if (fd != null) {
                    if (fd.startsWith("/")) {
                        fd = Server.get().getAddress() + "/file" + fd.replace(Path.rootPath(), "")
                    } else if (fd.startsWith("file")) {
                        fd = Server.get().getAddress() + "/" + fd.replace(Path.rootPath(), "").replace("://", "")
                    }
                    if (fd.contains("127.0.0.1")) fd = fd.replace("127.0.0.1", Util.getIp())
                }
                if (fd != null && fd != originalPic) history.vodPic = fd
                currentBody.add("history", history.toString())
                if (fd != null && fd != originalPic) history.vodPic = originalPic
            }
            getSelectedAudioTrackFormat()?.let {
                currentBody.add("audioTrackFormat", it)
            }
        }
        return currentBody
    }

    open fun history(history: History?): CastDialogV2 {
        historyData = history
        return this
    }

    open fun video(video: CastVideo?): CastDialogV2 {
        this.video = video
        return this
    }

    open fun queue(queue: CastQueue?): CastDialogV2 {
        this.queue = queue
        return this
    }

    open fun fm(fm: Boolean): CastDialogV2 {
        this.fm = fm
        return this
    }

    open fun onCasted(callback: Runnable?): CastDialogV2 {
        onCastedCallback = callback
        return this
    }

    open fun player(player: PlayerManager?): CastDialogV2 {
        this.player = player
        return this
    }

    open fun show(activity: FragmentActivity) {
        if (isDialogShowing(activity)) return
        show(activity.supportFragmentManager, null)
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogDeviceBinding.inflate(inflater, container, false).also {
            binding =
                it
        }

    override fun releaseBinding() {
        binding = null
    }

    override fun onStart() {
        super.onStart()
        val view = view
        if (view != null) {
            val bottomSheet = view.parent as FrameLayout?
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                val activity = activity
                if (activity != null) {
                    behavior.isFitToContents = false
                    behavior.expandedOffset = ResUtil.getScreenHeight(activity) / 2
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
    }

    override fun initView() {
        val currentBinding = binding
        if (currentBinding != null) {
            currentBinding.scan.visibility = if (fm) View.VISIBLE else View.GONE
        }
        updateDisconnectButton()
        updateModeButton()
        setRecyclerView()
        setupAudioTrackSpinner()
        getDevice()
    }

    override fun initEvent() {
        val currentBinding = binding
        if (fm && currentBinding != null) {
            currentBinding.scan.setOnClickListener {
                onScan()
            }
        }
        currentBinding?.profiles?.setOnClickListener {
            com.wuming.android.tv.cast.RendererCapabilityProfiles.remembered { profiles ->
                val context = context ?: return@remembered
                if (!isAdded) return@remembered
                if (profiles == null) { Notify.show(R.string.renderer_profile_unavailable); return@remembered }
                if (profiles.isEmpty()) { Notify.show(R.string.renderer_profiles_empty); return@remembered }
                com.wuming.android.tv.utils.TvAlertDialogBuilder(context)
                    .setTitle(R.string.renderer_profiles_saved)
                    .setItems(profiles.map { it.name + " · " + it.model }.toTypedArray()) { _, index ->
                        showRendererProfile(profiles[index])
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        currentBinding?.refresh?.setOnClickListener {
            onRefresh()
        }
        currentBinding?.disconnect?.setOnClickListener {
            onDisconnect()
        }
        currentBinding?.mode?.setOnClickListener {
            onToggleMode()
        }
    }

    private fun setRecyclerView() {
        val currentBinding = binding
        if (currentBinding != null) {
            currentBinding.recycler.setHasFixedSize(true)
            adapter = DeviceAdapter(this)
            currentBinding.recycler.adapter = adapter
        }
    }

    private fun setupAudioTrackSpinner() {
        audioTrackNames.clear()
        audioTrackFormats.clear()
        audioTrackNames.add(getString(R.string.audio_track_default))
        audioTrackFormats.add(null)
        val currentPlayer = player
        if (currentPlayer != null && currentPlayer.haveTrack(C.TRACK_TYPE_AUDIO)) {
            val currentFormat = currentPlayer.getSelectedAudioTrackFormat()
            for (group in currentPlayer.getCurrentTracks().groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val name = DefaultTrackNameProvider(App.get().resources).getTrackName(format)
                    val formatDesc = PlayerHelper.describeFormat(format)
                    audioTrackNames.add(name)
                    audioTrackFormats.add(formatDesc)
                    if (formatDesc == currentFormat) selectedAudioTrackIndex = audioTrackNames.size - 1
                }
            }
        }
        val currentBinding = binding
        if (currentBinding != null) {
            if (audioTrackNames.size > 2) {
                currentBinding.audioTrackRow.visibility = View.VISIBLE
                val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, audioTrackNames)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                currentBinding.audioTrackSpinner.adapter = spinnerAdapter
                currentBinding.audioTrackSpinner.setSelection(selectedAudioTrackIndex)
                currentBinding.audioTrackSpinner.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long,
                        ) {
                            selectedAudioTrackIndex = position
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {
                        }
                    }
            } else {
                currentBinding.audioTrackRow.visibility = View.GONE
            }
        }
    }

    private fun getSelectedAudioTrackFormat(): String? =
        if (selectedAudioTrackIndex >= 0 &&
            selectedAudioTrackIndex < audioTrackFormats.size
        ) {
            audioTrackFormats[selectedAudioTrackIndex]
        } else {
            null
        }

    private fun getCastVideoWithSelectedTrack(): CastVideo? {
        val video = video ?: return null
        val selectedFormat = getSelectedAudioTrackFormat()
        if (selectedFormat == null && video.audioTrackFormat == null) return video
        if (selectedFormat != null && selectedFormat == video.audioTrackFormat) return video
        return CastVideo(
            video.name,
            video.url,
            video.position,
            video.duration,
            video.headers,
            video.nextName,
            video.nextUrl,
            video.siteKey,
            video.flag,
            selectedFormat,
        )
    }

    private fun getDevice() {
        val currentAdapter = adapter ?: return
        currentAdapter.setItems(Device.getAll()) {
            val currentBinding = binding
            if (currentBinding != null && adapter === currentAdapter) {
                if (currentAdapter.itemCount == 0) onRefresh() else startDiscovery()
            }
        }
    }

    private fun startDiscovery() {
        ProjectionManager.get().stopDiscovery()
        ProjectionManager.get().discover(this)
    }

    private fun onScan() {
        val activity = activity ?: return
        val intent = Intent().setComponent(ComponentName(activity.packageName, SCAN_ACTIVITY_CLASS))
        if (intent.resolveActivity(activity.packageManager) != null) launcher.launch(intent)
    }

    private fun onRefresh() {
        val currentAdapter = adapter ?: return
        val currentScanTask = scanTask ?: return
        currentAdapter.clear {
            if (binding != null && adapter === currentAdapter && scanTask === currentScanTask) {
                Device.delete()
                currentScanTask.start()
                startDiscovery()
            }
        }
    }

    private fun onCasted() {
        onCastedCallback?.run()
        dismissAllowingStateLoss()
    }

    private fun onDisconnect() {
        ProjectionManager.get().disconnect()
        updateDisconnectButton()
        Notify.show(R.string.device_disconnect)
    }

    private fun updateDisconnectButton() {
        val binding = binding ?: return
        if (ProjectionManager.get().connectedDevice != null) {
            binding.disconnect.visibility = View.VISIBLE
            binding.disconnect.contentDescription = getString(R.string.device_disconnect)
        } else {
            binding.disconnect.visibility = View.GONE
        }
    }

    private fun updateModeButton() {
        val binding = binding ?: return
        val mode = DisplaySettings.getDlnaCastMode()
        binding.mode.visibility = View.VISIBLE
        binding.mode.setImageResource(
            when (mode) {
                0 -> R.drawable.ic_sync_two
                1 -> R.drawable.ic_action_direct
                else -> R.drawable.ic_cast_mobile
            },
        )
        binding.mode.contentDescription =
            getString(
                when (mode) {
                    0 -> R.string.dlna_mode_relay
                    1 -> R.string.dlna_mode_direct
                    else -> R.string.dlna_mode_auto
                },
            )
    }

    private fun onToggleMode() {
        val nextMode = (DisplaySettings.getDlnaCastMode() + 1) % 3
        DisplaySettings.putDlnaCastMode(nextMode)
        updateModeButton()
        Notify.show(
            when (nextMode) {
                0 -> R.string.dlna_mode_relay
                1 -> R.string.dlna_mode_direct
                else -> R.string.dlna_mode_auto
            },
        )
    }

    override fun onDeviceFound(device: Device) {
        adapter?.sort(device)
    }

    override fun onDeviceLost(device: Device) {
        adapter?.remove(device)
        val connected = ProjectionManager.get().connectedDevice
        if (connected != null && connected.getUuid().isNotEmpty() && connected.getUuid() == device.getUuid()) {
            ProjectionManager.get().disconnect()
            App.post {
                updateDisconnectButton()
            }
        }
    }

    override fun onFind(device: Device) {
        adapter?.sort(device)
    }

    override fun onItemClick(item: Device) {
        if (item.isApp()) {
            pauseLocalPlayback()
            val controlClient = client ?: return
            val handle =
                ProjectionManager.get().beginRemoteCast(
                    item,
                    video?.name ?: "",
                    hasPortableCloudRequest(),
                ) { sessionId ->
                    stopRemoteAppCast(controlClient, item, sessionId)
                }
            val portable = portableCloudRequest()
            val action = if (portable != null || (historyData == null && video != null)) "push" else "cast"
            val videoUrl = video?.url
            val baseBody = portable ?: if (action == "push" && videoUrl != null) pushRequest(videoUrl) else getBody().build()
            val requestBody = withCastSession(baseBody, handle.sessionId())
            RemoteAccess
                .newAuthorizedCall(
                    controlClient,
                    item.getIp() + "/action?do=" + action,
                    requestBody,
                ).enqueue(CastCallback(this, controlClient, handle, 0))
        } else {
            val castVideo = getCastVideoWithSelectedTrack()
            if (castVideo == null) {
                Notify.show(R.string.cast_no_video)
                return
            }
            if (ProjectionManager.get().isHlsUnsupportedDevice(item)) Notify.show(R.string.cast_compat_mode)
            pauseLocalPlayback()
            val castQueue = queue?.withAudioTrackFormat(castVideo.audioTrackFormat)
            Task.execute {
                ProjectionManager.get().cast(item, castVideo, castQueue) {
                    onCasted()
                }
            }
        }
    }

    // Pause even while buffering; casting must win over all local playback preparation.
    private fun pauseLocalPlayback() {
        player?.pause()
    }

    /** Process-private cloud handles must become expiring LAN URLs for another receiver. */
    private fun portableCloudRequest(): FormBody? {
        if (!hasPortableCloudRequest()) return null
        val video = video ?: return null
        val videoUrl = video.url ?: return null
        var target = videoUrl
        val phoneProxy = target.startsWith(Server.get().getAddress() + "/proxy")
        if (ProjectionManager.preferRelayForOriginalQuark(video.flag, phoneProxy)) {
            target =
                ProjectionManager.markParallelQuarkCast(target)
        }
        var authorized = CastMediaAccess.authorize(target)
        if (authorized == target) authorized = CastMediaAccess.relayDesktop(target, video.headers, false)
        if (authorized.isNullOrEmpty()) return null
        return pushRequest(authorized)
    }

    private fun hasPortableCloudRequest(): Boolean = historyData?.hasMediaUri() == true && video != null && !video?.url.isNullOrEmpty()

    private fun pushRequest(url: String): FormBody {
        val request = FormBody.Builder().add("url", url).add("cast", "true")
        video?.let {
            it.name?.let { name ->
                request.add("name", name)
            }
            request.add("position", it.position.toString())
            it.audioTrackFormat?.let { format ->
                request.add("audioTrackFormat", format)
            }
        }
        return request.build()
    }

    override fun onLongClick(item: Device): Boolean {
        if (item.isDLNA()) {
            val identity = com.wuming.android.tv.cast.RendererCapabilityProfiles.identityFor(item)
            if (identity != null) showRendererProfile(identity) else Notify.show(R.string.renderer_profile_unavailable)
            return true
        }
        Notify.show("Device: " + item.getName() + "\nUUID: " + item.getUuid() + "\nIP: " + item.getIp())
        return true
    }

    private var capabilityProbe: com.wuming.android.tv.cast.RendererPlaybackProbe? = null

    private fun showRendererProfile(item: com.wuming.tv.core.playback.renderer.RendererIdentity) {
        Notify.show(R.string.renderer_querying)
        com.wuming.android.tv.cast.RendererCapabilityProfiles.inspect(item) { status ->
            if (isAdded) renderRendererProfile(item, status)
        }
    }

    private fun renderRendererProfile(item: com.wuming.tv.core.playback.renderer.RendererIdentity, status: String) {
        com.wuming.android.tv.cast.RendererCapabilityProfiles.export(item) { json ->
            val context = context ?: return@export
            if (!isAdded) return@export
            if (json == null) { Notify.show(R.string.renderer_profile_unavailable); return@export }
            val profile = com.google.gson.JsonParser.parseString(json).asJsonObject
            val capabilities = com.wuming.tv.core.playback.renderer.RendererCapability.entries
            val labels = resources.getStringArray(R.array.renderer_capability_labels)
            val choices = resources.getStringArray(R.array.renderer_capability_choices)
            val states = com.wuming.tv.core.playback.renderer.CapabilityState.entries
            val pairingRequired = status == "pairing_required"
            val rows = capabilities.mapIndexed { index, capability ->
                val state = states.first { it.name == profile.getAsJsonObject("capabilities").get(capability.name).asString }
                val assessment = profile.getAsJsonObject("assessments").getAsJsonObject(capability.name)
                val advertised = assessment.get("advertised")?.asBoolean == true
                val manual = profile.getAsJsonObject("overrides").has(capability.name)
                val description = if (state != com.wuming.tv.core.playback.renderer.CapabilityState.UNKNOWN || manual) choices[state.ordinal]
                    else if (assessment.get("unknownReason")?.asString == "conflicting_evidence") getString(R.string.renderer_evidence_conflicting)
                    else if (advertised) getString(R.string.renderer_advertised)
                    else getString(when (assessment.get("unknownReason")?.asString) {
                        "conflicting_evidence" -> R.string.renderer_evidence_conflicting
                        "insufficient_evidence" -> R.string.renderer_evidence_insufficient
                        else -> R.string.renderer_not_verified
                    })
                labels[index] + ": " + description + if (manual) " · " + getString(R.string.renderer_manual_override) else ""
            }.toTypedArray()
            com.wuming.android.tv.utils.TvAlertDialogBuilder(context)
                .setTitle(item.name + " · " + getString(R.string.renderer_capabilities) + "\n" + rendererQueryMessage(status))
                .setItems(arrayOf(getString(R.string.renderer_query_retry), getString(R.string.renderer_run_test)) + rows) { _, position ->
                    val row = position - if (pairingRequired) 1 else 0
                    if (row < 0) return@setItems
                    if (row == 0) { showRendererProfile(item); return@setItems }
                    if (row == 1) { showRendererProbe(item); return@setItems }
                    val index = row - 2
                    com.wuming.android.tv.utils.TvAlertDialogBuilder(context)
                        .setTitle(labels[index])
                        .setItems(choices) { _, choice ->
                            com.wuming.android.tv.cast.RendererCapabilityProfiles.setOverride(item, capabilities[index], states.getOrNull(choice)) { ok ->
                                if (ok && isAdded) renderRendererProfile(item, status) else if (!ok) Notify.show(R.string.renderer_profile_unavailable)
                            }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
                .setPositiveButton(R.string.renderer_profile_export) { _, _ ->
                    com.wuming.android.tv.utils.Util.copySilent(json)
                    Notify.show(R.string.renderer_profile_copied)
                }
                .setNeutralButton(R.string.renderer_profile_reset) { _, _ ->
                    com.wuming.android.tv.utils.TvAlertDialogBuilder(context)
                        .setMessage(R.string.renderer_profile_reset_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            com.wuming.android.tv.cast.RendererCapabilityProfiles.reset(item) { ok ->
                                if (ok && isAdded) renderRendererProfile(item, status) else if (!ok) Notify.show(R.string.renderer_profile_unavailable)
                            }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }.setNegativeButton(android.R.string.cancel, null).create().apply {
                    setOnShowListener {
                        if (pairingRequired) {
                            // A scrollable, non-actionable header keeps the instructions readable
                            // without crowding the title or taking D-pad focus from Retry.
                            val guidance = android.widget.TextView(listView.context).apply {
                                text = getString(R.string.renderer_pairing_instructions)
                                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
                                val padding = (24 * resources.displayMetrics.density).toInt()
                                setPadding(padding, padding / 2, padding, padding / 2)
                            }
                            listView.addHeaderView(guidance, null, false)
                        }
                        findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
                            maxLines = 6
                            ellipsize = null
                        }
                        // Keep all three actions visible at large font sizes; capabilities scroll.
                        listView.layoutParams = listView.layoutParams.apply {
                            height = (resources.displayMetrics.heightPixels * 0.40f).toInt()
                        }
                    }
                }.show()
        }
    }

    private fun rendererQueryMessage(status: String): String {
        val http = status.removePrefix("query_http_").toIntOrNull()
        val upnp = status.removePrefix("query_upnp_").toIntOrNull()
        return when {
            http == 401 || http == 403 -> getString(R.string.renderer_query_denied, http)
            http != null -> getString(R.string.renderer_query_http_error, http)
            upnp != null -> getString(R.string.renderer_query_upnp_error, upnp)
            else -> getString(when (status) {
                "pairing_required" -> R.string.renderer_pairing_required
                "tested" -> R.string.renderer_test_saved
                "queried" -> R.string.renderer_query_complete
                "offline" -> R.string.renderer_query_offline
                "no_protocol_info", "empty_protocol_info" -> R.string.renderer_query_empty
                "timeout" -> R.string.renderer_query_timeout
                "storage_error" -> R.string.renderer_profile_unavailable
                else -> R.string.renderer_query_failed
            })
        }
    }

    private fun showRendererProbe(identity: com.wuming.tv.core.playback.renderer.RendererIdentity) {
        val context = context ?: return
        val device = com.wuming.android.tv.cast.RendererCapabilityProfiles.liveDevice(identity)
        if (device == null) { Notify.show(R.string.renderer_query_offline); return }
        val samples = com.wuming.tv.core.playback.renderer.RendererProbeSamples.samples
        com.wuming.android.tv.utils.TvAlertDialogBuilder(context)
            .setTitle(R.string.renderer_run_test)
            .setItems(samples.map { it.label }.toTypedArray()) { _, index ->
                com.wuming.android.tv.utils.TvAlertDialogBuilder(context)
                    .setMessage(R.string.renderer_test_start)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        capabilityProbe?.close()
                        val probe = com.wuming.android.tv.cast.RendererPlaybackProbe(device, samples[index])
                        capabilityProbe = probe
                        try { probe.start() } catch (_: Exception) { Notify.show(R.string.renderer_query_failed); return@setPositiveButton }
                        com.wuming.android.tv.utils.TvAlertDialogBuilder(context)
                            .setTitle(samples[index].label)
                            .setMessage(R.string.renderer_test_observe)
                            .setPositiveButton(R.string.renderer_test_success) { _, _ ->
                                if (probe.fetchedMedia) {
                                    com.wuming.android.tv.cast.RendererCapabilityProfiles.confirmProbe(identity, probe.sample, probe.runId) { ok ->
                                        if (ok && isAdded) renderRendererProfile(identity, "tested")
                                        else if (!ok) Notify.show(R.string.renderer_profile_unavailable)
                                    }
                                } else Notify.show(R.string.renderer_test_not_fetched)
                            }
                            .setNegativeButton(R.string.renderer_test_inconclusive) { _, _ ->
                                com.wuming.android.tv.cast.RendererCapabilityProfiles.inconclusiveProbe(identity, probe.sample, probe.runId)
                            }
                            .create().apply { setOnDismissListener { probe.close(); if (capabilityProbe === probe) capabilityProbe = null } }.show()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onDestroyView() {
        capabilityProbe?.close()
        capabilityProbe = null
        super.onDestroyView()
        ProjectionManager.get().stopDiscovery()
        scanTask?.stop()
        scanTask = null
        onCastedCallback = null
        historyData = null
        queue = null
        video = null
        body = null
        adapter = null
        player = null
        client = null
        audioTrackNames.clear()
        audioTrackFormats.clear()
    }

    private class CastCallback(
        dialog: CastDialogV2?,
        private val client: OkHttpClient,
        private val handle: ProjectionManager.RemoteCastHandle,
        private val attempt: Int,
    ) : Callback {
        private val dialogRef = WeakReference(dialog)

        override fun onFailure(
            call: Call,
            e: IOException,
        ) {
            if (attempt == 0 && !call.isCanceled() && ProjectionManager.get().isRemoteCast(handle)) {
                client.newCall(call.request()).enqueue(CastCallback(dialogRef.get(), client, handle, 1))
                return
            }
            ProjectionManager.get().failRemoteCast(handle)
            val dialog = dialogRef.get()
            val message = e.message
            if (dialog != null && message != null) {
                App.post {
                    Notify.show(message)
                }
            }
        }

        @Throws(IOException::class)
        override fun onResponse(
            call: Call,
            response: Response,
        ) {
            response.use { res ->
                val dialog = dialogRef.get()
                if (res.code == 401) {
                    ProjectionManager.get().failRemoteCast(handle)
                    if (dialog != null) {
                        App.post {
                            Notify.show(R.string.remote_pairing_required)
                        }
                    }
                    return
                }
                // Status acknowledges dispatch; never wait for an old receiver's open body.
                if (res.isSuccessful) {
                    ProjectionManager.get().confirmRemoteCast(handle)
                    if (dialog != null) {
                        App.post {
                            dialog.onCasted()
                        }
                    }
                } else {
                    ProjectionManager.get().failRemoteCast(handle)
                    if (dialog != null) {
                        App.post {
                            Notify.show(R.string.cast_failed)
                        }
                    }
                }
            }
        }
    }

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null && scanTask != null) {
                val address = result.data?.getStringExtra("address")
                if (!address.isNullOrEmpty()) scanTask?.start(address)
            }
        }

    companion object {
        private const val SCAN_ACTIVITY_CLASS = "com.wuming.android.tv.ui.activity.ScanActivity"

        @JvmStatic fun create(): CastDialogV2 = CastDialogV2()

        // Independent LAN transport: no provider cookies, proxy, dispatcher or pool contention.
        private fun createControlClient(): OkHttpClient {
            val dispatcher = Dispatcher()
            dispatcher.maxRequests = 8
            dispatcher.maxRequestsPerHost = 4
            return OkHttpClient
                .Builder()
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(4, 1, TimeUnit.MINUTES))
                .proxy(Proxy.NO_PROXY)
                .cache(null)
                .connectTimeout(Constant.TIMEOUT_SYNC, TimeUnit.MILLISECONDS)
                .readTimeout(
                    Constant.TIMEOUT_SYNC,
                    TimeUnit.MILLISECONDS,
                ).writeTimeout(Constant.TIMEOUT_SYNC, TimeUnit.MILLISECONDS)
                .callTimeout(Constant.TIMEOUT_SYNC, TimeUnit.MILLISECONDS)
                .build()
        }

        private fun withCastSession(
            original: FormBody,
            sessionId: String,
        ): FormBody {
            val result = FormBody.Builder()
            for (index in 0 until original.size) result.add(original.name(index), original.value(index))
            return result.add("castSession", sessionId).build()
        }

        private fun stopRemoteAppCast(
            client: OkHttpClient?,
            item: Device?,
            sessionId: String,
        ) {
            if (client == null || item == null || item.getIp().isEmpty()) return
            val body =
                FormBody
                    .Builder()
                    .add("type", "stop")
                    .add("castSession", sessionId)
                    .build()
            RemoteAccess.newAuthorizedCall(client, item.getIp() + "/action?do=control", body).enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                    } // Local cleanup never waits for receiver availability.

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.close()
                    }
                },
            )
        }
    }
}
