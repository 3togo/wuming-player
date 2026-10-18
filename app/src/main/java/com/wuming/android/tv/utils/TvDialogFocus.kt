package com.wuming.android.tv.utils

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import com.wuming.android.tv.R
import java.util.Collections
import java.util.WeakHashMap

/** Explicit remote navigation for shared dialogs; phone behavior is unchanged. */
class TvDialogFocus private constructor() {
    companion object {
        /** Weak keys avoid retaining rows; the window owns and detaches its layout listener. */
        @JvmStatic fun install(root: View) {
            if (!Util.isLeanback() || root.getTag(R.id.tv_remote_focus_installed) != null) return
            root.setTag(R.id.tv_remote_focus_installed, true)
            val prepared = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
            val observer = root.viewTreeObserver
            val listener =
                ViewTreeObserver.OnGlobalLayoutListener {
                    prepare(root, prepared)
                }
            observer.addOnGlobalLayoutListener(listener)
            root.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        v.removeOnAttachStateChangeListener(this)
                        if (observer.isAlive) {
                            observer.removeOnGlobalLayoutListener(listener)
                        } else {
                            v.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                        }
                    }
                },
            )
        }

        private fun prepare(
            view: View,
            prepared: MutableSet<View>,
        ) {
            if (view is ListView && prepared.add(view)) {
                view.setSelector(R.drawable.selector_video_rank_dialog_item)
                view.setDrawSelectorOnTop(false)
            }
            if ((view.isClickable || view.isLongClickable || view is EditText) && prepared.add(view)) {
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                // Foreground preserves the original widget colors, checked state and ripple.
                val outline = StateListDrawable()
                val focused = GradientDrawable()
                focused.setColor(Color.TRANSPARENT)
                val density = view.resources.displayMetrics.density
                focused.cornerRadius = 4 * density
                focused.setStroke(maxOf(2, Math.round(3 * density)), Color.WHITE)
                outline.addState(intArrayOf(android.R.attr.state_focused), focused)
                outline.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                val original = view.foreground
                view.foreground = if (original == null) outline else LayerDrawable(arrayOf(original, outline))
                if (view is EditText) {
                    view.setOnKeyListener { field, code, event ->
                        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return@setOnKeyListener false
                        val target = field.focusSearch(if (code == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                        if (target == null || target === field) return@setOnKeyListener false
                        if (event.action == KeyEvent.ACTION_DOWN) target.requestFocus()
                        true
                    }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) prepare(view.getChildAt(i), prepared)
        }

        @JvmStatic fun list(dialog: AlertDialog) {
            if (!Util.isLeanback()) return
            val list = dialog.listView ?: return
            list.setSelector(R.drawable.selector_video_rank_dialog_item)
            list.setDrawSelectorOnTop(false)
            list.isFocusable = true
            list.isFocusableInTouchMode = true
            if (list.selectedItemPosition < 0 && list.count > 0) list.setSelection(maxOf(0, list.checkedItemPosition))
            list.requestFocus()
        }

        @JvmStatic fun form(
            dialog: AlertDialog,
            vararg fields: EditText,
        ) {
            if (!Util.isLeanback()) return
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            for (field in fields) if (field.id == View.NO_ID) field.id = View.generateViewId()
            val remote =
                View.OnKeyListener { view, keyCode, event ->
                    if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@OnKeyListener false
                    if (event.action == KeyEvent.ACTION_UP) return@OnKeyListener true
                    val target = view.focusSearch(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                    target != null && target !== view && target.requestFocus()
                }
            for (i in fields.indices) {
                val field = fields[i]
                val next = if (i + 1 < fields.size) fields[i + 1] else positive
                field.nextFocusDownId = next.id
                field.nextFocusForwardId = next.id
                field.nextFocusUpId = if (i > 0) fields[i - 1].id else negative.id
                field.imeOptions = if (i + 1 < fields.size) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
                field.setOnKeyListener(remote)
                field.setOnEditorActionListener { _, action, _ ->
                    if (action != EditorInfo.IME_ACTION_NEXT && action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
                    next.requestFocus()
                    true
                }
            }
            positive.nextFocusLeftId = negative.id
            negative.nextFocusRightId = positive.id
            positive.nextFocusDownId = fields[0].id
            negative.nextFocusDownId = fields[0].id
            positive.nextFocusUpId = fields.last().id
            negative.nextFocusUpId = fields.last().id
            positive.setOnKeyListener(remote)
            negative.setOnKeyListener(remote)
            positive.requestFocus()
        }
    }
}
