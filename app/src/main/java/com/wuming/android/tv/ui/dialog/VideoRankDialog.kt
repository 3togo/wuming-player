package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.wuming.android.tv.utils.Util
import com.wuming.android.tv.utils.VideoRankManager
import com.google.android.material.slider.Slider

class VideoRankDialog private constructor() {
    companion object {
        private const val MIN_SCORE = -1
        private const val MAX_SCORE = 10

        @JvmStatic fun manage(
            activity: Activity,
            onChanged: Runnable?,
        ) {
            val entries = VideoRankManager.getAll()
            if (entries.isEmpty()) {
                val dialog =
                    TvAlertDialogBuilder(
                        activity,
                        R.style.DarkDialogStyle,
                    ).setTitle(
                        R.string.video_rankings,
                    ).setMessage(R.string.video_rankings_empty)
                        .setPositiveButton(R.string.dialog_positive, null)
                        .create()
                showEnhanced(dialog, null)
                return
            }
            val labels =
                Array(entries.size) { i -> activity.getString(R.string.video_rank_item, entries[i].getRank(), entries[i].getTitle()) }
            val dialog =
                TvAlertDialogBuilder(activity, R.style.DarkDialogStyle)
                    .setTitle(R.string.video_rankings)
                    .setItems(labels) { _, which -> editEntry(activity, entries[which], onChanged) }
                    .setNeutralButton(R.string.video_rank_clear_all) { _, _ ->
                        VideoRankManager.clear()
                        run(onChanged)
                    }.setNegativeButton(R.string.dialog_negative, null)
                    .create()
            showEnhanced(dialog, null)
        }

        @JvmStatic fun editEpisode(
            activity: Activity,
            contentKey: String,
            sourceName: String,
            seriesTitle: String,
            episode: Episode,
            onChanged: Runnable?,
        ) {
            val episodeTitle = episode.getDesc() + episode.getName()
            val sourceTitle =
                if (sourceName.isEmpty()) {
                    seriesTitle
                } else {
                    activity.getString(
                        R.string.video_quality_source_title,
                        seriesTitle,
                        sourceName,
                    )
                }
            val scopes =
                arrayOf(
                    activity.getString(
                        R.string.video_quality_this_episode,
                        episodeTitle,
                        VideoRankManager.getEpisodeRank(contentKey, episode),
                    ),
                    activity.getString(R.string.video_quality_whole_series, seriesTitle, VideoRankManager.getContentRank(contentKey)),
                    activity.getString(R.string.video_rank_manage),
                )
            val dialog =
                TvAlertDialogBuilder(
                    activity,
                    R.style.DarkDialogStyle,
                ).setTitle(R.string.video_quality_rank_scope)
                    .setItems(scopes) { _, which ->
                        if (which ==
                            0
                        ) {
                            showEditor(
                                activity,
                                episodeTitle,
                                VideoRankManager.getEpisodeRank(contentKey, episode),
                                { rank -> VideoRankManager.setEpisode(contentKey, sourceTitle, episode, rank) },
                                onChanged,
                            )
                        } else if (which ==
                            1
                        ) {
                            showEditor(
                                activity,
                                sourceTitle,
                                VideoRankManager.getContentRank(contentKey),
                                { rank -> VideoRankManager.setContent(contentKey, sourceTitle, rank) },
                                onChanged,
                            )
                        } else {
                            manage(activity, onChanged)
                        }
                    }.setNegativeButton(R.string.dialog_negative, null)
                    .create()
            showEnhanced(dialog, null)
        }

        private fun editEntry(
            activity: Activity,
            entry: VideoRankManager.Entry,
            onChanged: Runnable?,
        ) {
            showEditor(activity, entry.getTitle(), entry.getRank(), { rank -> VideoRankManager.set(entry, rank) }, onChanged)
        }

        private fun showEditor(
            activity: Activity,
            title: String,
            currentRank: Int,
            saver: (Int) -> Unit,
            onChanged: Runnable?,
        ) {
            val margin = (24 * activity.resources.displayMetrics.density).toInt()
            val spacing =
                (
                    12 *
                        activity.resources.displayMetrics.density
                ).toInt()
            val value = TextView(activity)
            value.gravity = Gravity.CENTER
            value.setTextColor(Color.WHITE)
            val slider = Slider(activity)
            slider.valueFrom = MIN_SCORE.toFloat()
            slider.valueTo = MAX_SCORE.toFloat()
            slider.stepSize = 1f
            slider.value =
                maxOf(MIN_SCORE, minOf(MAX_SCORE, currentRank)).toFloat()
            slider.thumbTintList = ColorStateList.valueOf(Color.WHITE)
            slider.trackActiveTintList = ColorStateList.valueOf(Color.WHITE)
            slider.trackInactiveTintList =
                ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
            slider.tickActiveTintList = ColorStateList.valueOf(Color.BLACK)
            slider.tickInactiveTintList =
                ColorStateList.valueOf(Color.WHITE)
            slider.contentDescription = activity.getString(R.string.video_score_slider_description)
            slider.addOnChangeListener { _, score, _ -> value.text = activity.getString(R.string.video_score_value, Math.round(score)) }
            value.text = activity.getString(R.string.video_score_value, Math.round(slider.value))
            val scale = TextView(activity)
            scale.setText(R.string.video_score_scale)
            scale.gravity = Gravity.CENTER
            scale.setTextColor(Color.argb(180, 255, 255, 255))
            val container = LinearLayout(activity)
            container.orientation = LinearLayout.VERTICAL
            container.setPadding(margin, spacing, margin, 0)
            container.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(scale, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val dialog =
                TvAlertDialogBuilder(
                    activity,
                    R.style.DarkDialogStyle,
                ).setTitle(activity.getString(R.string.video_rank_title, title))
                    .setMessage(R.string.video_rank_hint)
                    .setView(container)
                    .setPositiveButton(
                        R.string.dialog_positive,
                        null,
                    ).setNeutralButton(
                        R.string.video_rank_manage,
                    ) { _, _ -> manage(activity, onChanged) }
                    .setNegativeButton(R.string.dialog_negative, null)
                    .create()
            dialog.setOnShowListener {
                enhanceFocus(dialog, slider)
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    saver(Math.round(slider.value))
                    dialog.dismiss()
                    run(onChanged)
                }
                slider.post { slider.requestFocus() }
            }
            dialog.show()
        }

        private fun showEnhanced(
            dialog: androidx.appcompat.app.AlertDialog,
            slider: Slider?,
        ) {
            dialog.setOnShowListener {
                enhanceFocus(dialog, slider)
                val list = dialog.listView
                if (list != null) {
                    if (list.count > 0 && list.selectedItemPosition == AdapterView.INVALID_POSITION) list.setSelection(0)
                    list.requestFocus()
                    list.post { styleListItem(list.selectedView, true) }
                }
            }
            dialog.show()
        }

        private fun enhanceFocus(
            dialog: androidx.appcompat.app.AlertDialog,
            slider: Slider?,
        ) {
            if (!Util.isLeanback()) return
            val list = dialog.listView
            if (list !=
                null
            ) {
                list.setSelector(R.drawable.selector_video_rank_dialog_item)
                list.isDrawSelectorOnTop = false
                list.isFocusable =
                    true
                list.isFocusableInTouchMode = true
                styleList(list)
            }
            styleButton(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE))
            styleButton(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE))
            styleButton(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL))
            if (slider != null) styleSlider(slider)
        }

        private fun styleButton(button: Button?) {
            if (button == null) return
            val background = button.background
            val backgroundTint = button.backgroundTintList
            val textColors = button.textColors
            val typeface = button.typeface
            button.setOnFocusChangeListener {
                _,
                focused,
                ->
                applyButtonFocus(button, focused, background, backgroundTint, textColors, typeface)
            }
            applyButtonFocus(button, button.isFocused, background, backgroundTint, textColors, typeface)
        }

        private fun applyButtonFocus(
            button: Button,
            focused: Boolean,
            background: Drawable?,
            backgroundTint: ColorStateList?,
            textColors: ColorStateList,
            typeface: Typeface?,
        ) {
            if (focused) {
                button.backgroundTintList = null
                button.setBackgroundResource(R.drawable.shape_video_rank_focus)
                button.setTextColor(Color.WHITE)
                button.setTypeface(typeface, Typeface.BOLD)
                button.scaleX =
                    1.08f
                button.scaleY = 1.08f
            } else {
                button.background = background
                button.backgroundTintList = backgroundTint
                button.setTextColor(textColors)
                button.typeface =
                    typeface
                button.scaleX = 1f
                button.scaleY = 1f
            }
        }

        private fun styleList(list: ListView) {
            var selected: View? = null
            list.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        styleListItem(selected, false)
                        selected =
                            view
                        styleListItem(view, true)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {
                        styleListItem(selected, false)
                        selected = null
                    }
                }
            list.setOnFocusChangeListener { _, focused -> styleListItem(list.selectedView, focused) }
        }

        private fun styleListItem(
            view: View?,
            focused: Boolean,
        ) {
            if (view == null) return
            if (view is TextView) {
                view.setTextColor(if (focused) Color.WHITE else Color.argb(180, 255, 255, 255))
                view.setTypeface(Typeface.DEFAULT, if (focused) Typeface.BOLD else Typeface.NORMAL)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) styleListItem(view.getChildAt(i), focused)
            }
            view.scaleX = if (focused) 1.08f else 1f
            view.scaleY = if (focused) 1.08f else 1f
        }

        private fun styleSlider(slider: Slider) {
            slider.isFocusable = true
            slider.isFocusableInTouchMode = true
            slider.setOnFocusChangeListener { _, focused ->
                slider.setBackgroundResource(if (focused) R.drawable.shape_video_rank_focus else 0)
                slider.thumbStrokeColor = ColorStateList.valueOf(Color.BLACK)
                slider.thumbStrokeWidth =
                    if (focused) dp(slider, 3).toFloat() else 0f
                slider.haloTintList = ColorStateList.valueOf(if (focused) Color.argb(80, 255, 255, 255) else Color.TRANSPARENT)
                slider
                    .animate()
                    .scaleX(if (focused) 1.08f else 1f)
                    .scaleY(if (focused) 1.08f else 1f)
                    .setDuration(200)
                    .start()
            }
        }

        private fun dp(
            view: View,
            value: Int,
        ): Int = Math.round(value * view.resources.displayMetrics.density)

        private fun run(callback: Runnable?) {
            callback?.run()
        }
    }
}
