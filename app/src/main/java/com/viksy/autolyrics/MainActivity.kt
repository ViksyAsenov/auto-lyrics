package com.viksy.autolyrics

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

const val baseTextSize = 16f
const val basePadding = 24

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val statusText = TextView(this).apply {
            text = "Grant notification access to allow this app to read active song titles from your media player."
            textSize = baseTextSize
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER

            val marginDp = (basePadding * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, marginDp)
        }

        val button = Button(this).apply {
            text = "Enable Notification Access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        layout.addView(statusText)
        layout.addView(button)

        val basePaddingPx = (basePadding * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.setPadding(
                systemBars.left + basePaddingPx,
                systemBars.top + basePaddingPx,
                systemBars.right + basePaddingPx,
                systemBars.bottom + basePaddingPx
            )

            windowInsets
        }

        setContentView((layout))
    }
}