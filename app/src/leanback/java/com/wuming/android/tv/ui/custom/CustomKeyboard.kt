package com.wuming.android.tv.ui.custom

import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.ActivitySearchBinding
import com.wuming.android.tv.ui.adapter.KeyboardAdapter

open class CustomKeyboard(
    private val callback: Callback,
    private val binding: ActivitySearchBinding,
) : KeyboardAdapter.OnClickListener {
    private var adapter: KeyboardAdapter? = null

    companion object {
        @JvmStatic fun init(
            callback: Callback,
            binding: ActivitySearchBinding,
        ) {
            CustomKeyboard(callback, binding).initView()
        }
    }

    private fun initView() {
        binding.keyboard.itemAnimator = null
        binding.keyboard.setHasFixedSize(false)
        binding.keyboard.addItemDecoration(SpaceItemDecoration(7, R.dimen.vs_8))
        adapter = KeyboardAdapter(this)
        binding.keyboard.adapter = adapter
    }

    override fun onTextClick(text: String) {
        val sb = StringBuilder(binding.keyword.text.toString())
        val cursor = binding.keyword.selectionStart
        if (binding.keyword.length() > 19) return
        sb.insert(cursor, text)
        binding.keyword.setText(sb.toString())
        binding.keyword.setSelection(cursor + 1)
    }

    override fun onIconClick(resId: Int) {
        val sb = StringBuilder(binding.keyword.text.toString())
        val cursor = binding.keyword.selectionStart
        when (resId) {
            R.drawable.ic_setting_home -> callback.showDialog()
            R.drawable.ic_keyboard_remote -> callback.onRemote()
            R.drawable.ic_keyboard_search -> callback.onSearch()
            R.drawable.ic_keyboard_left -> onMoveLeft(cursor)
            R.drawable.ic_keyboard_right -> onMoveRight(cursor)
            R.drawable.ic_keyboard_back -> onBackspace(sb, cursor)
            R.drawable.ic_keyboard -> showInputMethodPicker()
        }
    }

    private fun showInputMethodPicker() {
        (binding.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?)?.showInputMethodPicker()
    }

    private fun onMoveLeft(cursor: Int) {
        binding.keyword.setSelection(maxOf(cursor - 1, 0))
    }

    private fun onMoveRight(cursor: Int) {
        binding.keyword.setSelection(minOf(cursor + 1, binding.keyword.length()))
    }

    private fun onBackspace(
        sb: StringBuilder,
        cursor: Int,
    ) {
        if (cursor <= 0) return
        sb.deleteCharAt(cursor - 1)
        binding.keyword.setText(sb.toString())
        binding.keyword.setSelection(cursor - 1)
    }

    override fun onLongClick(resId: Int): Boolean {
        if (resId != R.drawable.ic_keyboard_back) return false
        binding.keyword.setText("")
        return true
    }

    interface Callback {
        fun showDialog()

        fun onRemote()

        fun onSearch()
    }
}
