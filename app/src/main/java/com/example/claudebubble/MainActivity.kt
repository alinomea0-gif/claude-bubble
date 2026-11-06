package com.example.claudebubble

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var keyField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Claude Bubble"
            textSize = 24f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Paste your Anthropic API key, then tap Start."
            setPadding(0, pad / 2, 0, pad / 2)
        })
        keyField = EditText(this).apply {
            hint = "sk-ant-..."
            setSingleLine()
            setText(prefs.getString("key", ""))
        }
        root.addView(keyField)

        root.addView(Button(this).apply {
            text = "Start"
            setOnClickListener {
                prefs.edit().putString("key", keyField.text.toString().trim()).apply()
                proceed()
            }
        })
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, FloatingService::class.java)) }
        })
        setContentView(root)
    }

    private fun proceed() {
        if (keyField.text.isBlank()) {
            toast("Enter your API key first"); return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow 'Display over other apps', then come back and tap Start")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1); return
        }
        startForegroundService(Intent(this, FloatingService::class.java))
        toast("Bubble started. You can leave the app.")
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) proceed()
        else toast("Microphone permission is required")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
