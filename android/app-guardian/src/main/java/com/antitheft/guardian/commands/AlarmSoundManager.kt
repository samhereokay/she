package com.antitheft.guardian.commands

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

object AlarmSoundManager {
    private const val TAG = "AlarmSoundManager"
    private const val MAX_ALARM_DURATION_MS = 5 * 60 * 1000L // 5 minutes safety timeout
    private const val DEFAULT_VOICE_ALERT = "Warning. This device is reported stolen. Location is being tracked and reported. Return to owner immediately."

    private var mediaPlayer: MediaPlayer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false

    private val speechRepeatRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                speakWarning(DEFAULT_VOICE_ALERT)
                handler.postDelayed(this, 10000L) // repeat every 10 seconds
            }
        }
    }

    private val autoStopRunnable = Runnable {
        Log.i(TAG, "Alarm reached maximum duration (5 min). Automatically stopping.")
        stopAlarm()
    }

    @Synchronized
    fun startAlarm(context: Context, customPhrase: String? = null) {
        if (isPlaying) {
            Log.d(TAG, "Alarm is already playing.")
            return
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }

            // Initialize TTS
            val phrase = customPhrase ?: DEFAULT_VOICE_ALERT
            initAndSpeakTts(context.applicationContext, phrase)

            isPlaying = true
            handler.postDelayed(autoStopRunnable, MAX_ALARM_DURATION_MS)
            handler.postDelayed(speechRepeatRunnable, 3000L)
            Log.i(TAG, "High-priority anti-theft alarm + TTS voice alert started at maximum volume.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting anti-theft alarm sound", e)
        }
    }

    private fun initAndSpeakTts(appContext: Context, phrase: String) {
        textToSpeech = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.US
                isTtsReady = true
                speakWarning(phrase)
            } else {
                Log.w(TAG, "TTS initialization failed with code: $status")
            }
        }
    }

    private fun speakWarning(phrase: String) {
        try {
            if (isTtsReady && textToSpeech != null) {
                val params = android.os.Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                textToSpeech?.speak(phrase, TextToSpeech.QUEUE_FLUSH, params, "STOLEN_ALARM_UTTERANCE")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed", e)
        }
    }

    @Synchronized
    fun stopAlarm() {
        handler.removeCallbacks(autoStopRunnable)
        handler.removeCallbacks(speechRepeatRunnable)
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.stop()
                }
                mediaPlayer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Exception while stopping alarm", e)
            } finally {
                mediaPlayer = null
            }
        }
        if (textToSpeech != null) {
            try {
                textToSpeech?.stop()
                textToSpeech?.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Exception while shutting down TTS", e)
            } finally {
                textToSpeech = null
                isTtsReady = false
            }
        }
        isPlaying = false
        Log.i(TAG, "Alarm and TTS stopped.")
    }

    fun isAlarmActive(): Boolean = isPlaying
}
