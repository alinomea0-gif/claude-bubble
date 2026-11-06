package com.example.claudebubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class FloatingService : Service(), TextToSpeech.OnInitListener {

    companion object {
        // ---- Settings you may want to change ----
        const val MODEL = "claude-sonnet-5-5"
        const val SPEECH_LANG = "ar-IQ"          // speech recognition language
        val TTS_LOCALE: Locale = Locale("ar")    // spoken reply language
        const val SYSTEM_PROMPT =
            "You are a voice assistant. Reply in the same language and dialect the user speaks, " +
            "in 1 to 3 short spoken sentences. No markdown, no lists, no emojis."
        // -----------------------------------------
        const val CHANNEL = "bubble"
    }

    private enum class State(val color: Int) {
        IDLE(0xFF2979FF.toInt()),
        LISTENING(0xFFE53935.toInt()),
        THINKING(0xFFFB8C00.toInt()),
        SPEAKING(0xFF43A047.toInt())
    }

    private lateinit var wm: WindowManager
    private lateinit var bubble: View
    private lateinit var params: WindowManager.LayoutParams
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private val history = JSONArray()
    private var state = State.IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        createBubble()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Claude Bubble", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Claude Bubble is running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun createBubble() {
        val size = (64 * resources.displayMetrics.density).toInt()
        bubble = View(this)
        paint(State.IDLE)

        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        wm.updateViewLayout(bubble, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onBubbleTap()
            }
            true
        }
        wm.addView(bubble, params)
    }

    private fun paint(s: State) {
        state = s
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(s.color)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        bubble.elevation = 12f
    }

    private fun onBubbleTap() {
        when (state) {
            State.IDLE -> startListening()
            State.LISTENING -> recognizer?.stopListening()
            State.SPEAKING -> { tts?.stop(); paint(State.IDLE) }
            State.THINKING -> {}
        }
    }

    // ---------- Listening ----------
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition is not available on this phone"); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text.isNullOrBlank()) paint(State.IDLE) else ask(text)
                }
                override fun onError(error: Int) {
                    paint(State.IDLE)
                    if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) toast("Speech error: $error")
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, SPEECH_LANG)
        }
        paint(State.LISTENING)
        recognizer?.startListening(intent)
    }

    // ---------- Claude ----------
    private fun ask(text: String) {
        paint(State.THINKING)
        history.put(JSONObject().put("role", "user").put("content", text))
        val snapshot = JSONArray(history.toString())
        thread {
            try {
                val reply = callClaude(snapshot)
                handler.post {
                    history.put(JSONObject().put("role", "assistant").put("content", reply))
                    while (history.length() > 20) { history.remove(0); history.remove(0) }
                    speak(reply)
                }
            } catch (e: Exception) {
                handler.post {
                    history.remove(history.length() - 1)
                    toast("Error: ${e.message}")
                    paint(State.IDLE)
                }
            }
        }
    }

    private fun callClaude(messages: JSONArray): String {
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 500)
            .put("system", SYSTEM_PROMPT)
            .put("messages", messages)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw Exception("HTTP $code ${text.take(200)}")

        val content = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.getString("text"))
        }
        return sb.toString().trim()
    }

    // ---------- Speaking ----------
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts?.setLanguage(TTS_LOCALE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("Install an Arabic voice in your phone's text-to-speech settings")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { handler.post { paint(State.IDLE) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { handler.post { paint(State.IDLE) } }
        })
        ttsReady = true
    }

    private fun speak(reply: String) {
        if (!ttsReady || reply.isBlank()) {
            toast(reply); paint(State.IDLE); return
        }
        paint(State.SPEAKING)
        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        runCatching { wm.removeView(bubble) }
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
