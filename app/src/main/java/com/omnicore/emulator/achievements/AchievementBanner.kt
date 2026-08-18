package com.omnicore.emulator.achievements

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import java.util.WeakHashMap

/** Lightweight queued banner: multiple unlocks are shown sequentially instead of replacing each other. */
object AchievementBanner {
    private val pending = WeakHashMap<Activity, ArrayDeque<OmniAchievements.Unlock>>()
    private val active = WeakHashMap<Activity, Boolean>()

    fun show(activity: Activity, unlock: OmniAchievements.Unlock) = showAll(activity, listOf(unlock))

    fun showAll(activity: Activity, unlocks: List<OmniAchievements.Unlock>) {
        if (unlocks.isEmpty()) return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val queue = pending.getOrPut(activity) { ArrayDeque() }
            unlocks.distinctBy { it.definition.id }.forEach(queue::addLast)
            if (active[activity] != true) showNext(activity)
        }
    }

    private fun showNext(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            pending.remove(activity)
            active.remove(activity)
            return
        }
        val queue = pending[activity]
        val unlock = queue?.pollFirst()
        if (unlock == null) {
            active[activity] = false
            return
        }
        active[activity] = true
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            active[activity] = false
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(36, 29, 65), Color.rgb(13, 25, 42))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.argb(150, 157, 126, 255))
            }
            elevation = dp(10).toFloat()
            alpha = 0f
            translationY = -dp(70).toFloat()
        }

        panel.addView(TextView(activity).apply {
            text = unlock.definition.icon.ifBlank { "★" }
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 216, 48))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        copy.addView(TextView(activity).apply {
            text = "CONQUISTA • ${unlock.definition.rarity.label.uppercase()} • ${unlock.definition.points} PTS"
            textSize = 10f
            setTextColor(Color.rgb(153, 205, 255))
            setTypeface(typeface, Typeface.BOLD)
        })
        copy.addView(TextView(activity).apply {
            text = unlock.definition.title
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        copy.addView(TextView(activity).apply {
            text = unlock.definition.description
            textSize = 11f
            setTextColor(Color.rgb(185, 192, 214))
            maxLines = 2
        })
        panel.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { setMargins(dp(12), dp(14), dp(12), 0) }
        )
        panel.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        panel.postDelayed({
            panel.animate()
                .alpha(0f)
                .translationY(-dp(42).toFloat())
                .setDuration(200L)
                .withEndAction {
                    if (panel.parent === root) root.removeView(panel)
                    active[activity] = false
                    panel.postDelayed({ showNext(activity) }, 120L)
                }
                .start()
        }, 2450L)
    }
}
