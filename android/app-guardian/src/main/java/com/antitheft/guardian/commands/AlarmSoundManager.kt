package com.antitheft.guardian.commands

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

object AlarmSoundManager {
    private const val TAG = "AlarmSoundManager"
    private const val MAX_ALARM_DURATION_MS = 5 * 60 * 1000L // 5 minutes safety timeout

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false

    private val autoStopRunnable = Runnable {
        Log.i(TAG, "Alarm reached maximum duration (5 min). Automatically stopping.")
        stopAlarm()
    }

    @Synchronized
    fun startAlarm(context: Context) {
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

            isPlaying = true
            handler.postDelayed(autoStopRunnable, MAX_ALARM_DURATION_MS)
            Log.i(TAG, "High-priority anti-theft alarm started at maximum volume.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting anti-theft alarm sound", e)
        }
    }

    @Synchronized
    fun stopAlarm() {
        handler.removeCallbacks(autoStopRunnable)
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
                isPlaying = false
                Log.i(TAG, "Alarm stopped.")
            }
        }
    }

    fun isAlarmActive(): Boolean = isPlaying
}
