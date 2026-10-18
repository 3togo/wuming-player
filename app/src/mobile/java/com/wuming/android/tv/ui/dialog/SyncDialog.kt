package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.Intent
import android.content.res.TypedArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.App
import com.wuming.android.tv.Constant
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.bean.Device
import com.wuming.android.tv.databinding.DialogDeviceBinding
import com.wuming.android.tv.impl.Callback
import com.wuming.android.tv.library.UserLibrary
import com.wuming.android.tv.server.RemoteAccess
import com.wuming.android.tv.setting.AppSettings
import com.wuming.android.tv.ui.activity.ScanActivity
import com.wuming.android.tv.ui.adapter.DeviceAdapter
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.ScanTask
import com.github.catvod.net.OkHttp
import com.google.android.material.bottomsheet.BottomSheetBehavior
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.Locale

open class SyncDialog :
    BaseDialog(),
    DeviceAdapter.OnClickListener,
    ScanTask.Listener {
    private val body = FormBody.Builder()
    private var scanTask: ScanTask? = ScanTask(this)
    private val client = OkHttp.client(Constant.TIMEOUT_SYNC)
    private val mode = ResUtil.getTypedArray(R.array.cast_mode)
    private var binding: DialogDeviceBinding? = null
    private var adapter: DeviceAdapter? = null
    private var type: String? = null

    companion object {
        @JvmStatic fun create(): SyncDialog = SyncDialog()
    }

    open fun history(): SyncDialog {
        // These reads use the in-memory caches.
        body.add("device", Device.get().toString())
        body.add("config", VodConfig.get().getConfig().toString())
        body.add("targets", App.gson().toJson(UserLibrary.get().histories()))
        return type("history")
    }

    open fun keep(): SyncDialog {
        body.add("device", Device.get().toString())
        body.add("targets", App.gson().toJson(UserLibrary.get().videoFavorites()))
        body.add("configs", App.gson().toJson(Config.findUrls()))
        return type("keep")
    }

    open fun show(activity: FragmentActivity) {
        if (isDialogShowing(activity)) return
        show(activity.supportFragmentManager, null)
    }

    private fun type(type: String): SyncDialog {
        this.type = type
        return this
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
        val view = view ?: return
        val bottomSheet = view.parent as FrameLayout? ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        val activity = activity ?: return
        behavior.isFitToContents = false
        behavior.expandedOffset = ResUtil.getScreenHeight(activity) / 2
        behavior.state =
            BottomSheetBehavior.STATE_EXPANDED
    }

    override fun initView() {
        binding!!.mode.visibility = View.VISIBLE
        setRecyclerView()
        getDevice()
        setMode()
    }

    override fun initEvent() {
        binding!!.mode.setOnClickListener { onMode() }
        binding!!.scan.setOnClickListener { onScan() }
        binding!!.refresh.setOnClickListener { onRefresh() }
    }

    private fun setRecyclerView() {
        binding!!.recycler.setHasFixedSize(true)
        adapter = DeviceAdapter(this)
        binding!!.recycler.adapter =
            adapter
    }

    private fun getDevice() {
        val currentAdapter = adapter!!
        currentAdapter.setItems(Device.getAll()) {
            if (binding != null && adapter === currentAdapter &&
                currentAdapter.itemCount == 0
            ) {
                onRefresh()
            }
        }
    }

    private fun setMode() {
        val index = AppSettings.getSyncMode()
        binding!!.mode.setImageResource(mode.getResourceId(index, 0))
        binding!!.mode.tag =
            index.toString()
    }

    private fun onMode() {
        val previous = AppSettings.getSyncMode()
        val index = if (previous == mode.length() - 1) 0 else previous + 1
        AppSettings.putSyncMode(index)
        binding!!.mode.setImageResource(mode.getResourceId(index, 0))
        binding!!.mode.tag = index.toString()
    }

    private fun onScan() {
        val activity = activity ?: return
        launcher.launch(Intent(activity, ScanActivity::class.java))
    }

    private fun onRefresh() {
        val currentAdapter = adapter ?: return
        val currentScanTask = scanTask ?: return
        currentAdapter.clear {
            if (binding != null && adapter === currentAdapter &&
                scanTask === currentScanTask
            ) {
                Device.delete()
                currentScanTask.start()
            }
        }
    }

    private fun onSuccess() {
        dismiss()
    }

    override fun onFind(device: Device) {
        adapter!!.sort(device)
    }

    override fun onItemClick(item: Device) {
        RemoteAccess
            .newAuthorizedCall(
                client,
                String.format(Locale.getDefault(), "%s/action?do=sync&mode=%s&type=%s", item.getIp(), binding!!.mode.tag.toString(), type),
                body.build(),
            ).enqueue(getCallback())
    }

    override fun onLongClick(item: Device): Boolean {
        val mode = binding!!.mode.tag.toString()
        if (mode == "0") return false
        if (mode == "2") deleteLocal()
        val force = if (mode == "1") "&force=true" else ""
        RemoteAccess
            .newAuthorizedCall(
                client,
                String.format(Locale.getDefault(), "%s/action?do=sync&mode=%s&type=%s%s", item.getIp(), mode, type, force),
                body.build(),
            ).enqueue(getCallback())
        return true
    }

    private fun deleteLocal() {
        if (type!! == "keep") UserLibrary.get().deleteAllVideoFavorites()
        if (type == "history") UserLibrary.get().deleteHistoryCatalog(VodConfig.getCid())
    }

    private fun getCallback(): Callback =
        object : Callback() {
            override fun onResponse(
                call: Call,
                response: Response,
            ) {
                response.use { res ->
                    if (res.code == 401) {
                        App.post { Notify.show(R.string.remote_pairing_required) }
                    } else if (res.isSuccessful) {
                        App.post { onSuccess() }
                    } else {
                        val message = res.message
                        App.post { Notify.show(message) }
                    }
                }
            }

            override fun onFailure(
                call: Call,
                e: IOException,
            ) {
                App.post { Notify.show(e.message ?: "") }
            }
        }

    override fun onDestroyView() {
        super.onDestroyView()
        scanTask!!.stop()
        scanTask = null
    }

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null &&
                scanTask != null
            ) {
                scanTask!!.start(result.data!!.getStringExtra("address"))
            }
        }
}
