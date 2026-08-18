package com.omnicore.emulator.achievements

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object AchievementBanner {
    private const val TAG = "omnicore-achievement-banner"

    fun show(activity: Activity, unlock: OmniAchievements.Unlock) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
            root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }

            val density = activity.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val panel = LinearLayout(activity).apply {
                tag = TAG
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

            val star = TextView(activity).apply {
                text = "★"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 216, 48))
            }
            panel.addView(star, LinearLayout.LayoutParams(dp(44), dp(44)))

            val copy = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
            }
            copy.addView(TextView(activity).apply {
                text = "CONQUISTA • ${unlock.definition.rarity.label.uppercase()}"
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

            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(dp(12), dp(14), dp(12), 0)
            }
            root.addView(panel, params)
            panel.animate().alpha(1f).translationY(0f).setDuration(220L).start()
            panel.postDelayed({
                panel.animate()
                    .alpha(0f)
                    .translationY(-dp(42).toFloat())
                    .setDuration(240L)
                    .withEndAction { if (panel.parent === root) root.removeView(panel) }
                    .start()
            }, 2900L)
        }
    }
}
