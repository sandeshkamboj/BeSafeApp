package com.be.safe.services

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.be.safe.MainActivity
import com.be.safe.model.Command
import com.be.safe.model.FileModel
import com.be.safe.model.Options
import com.be.safe.supabase.SupabaseClient
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class BackgroundService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var mediaRecorder: MediaRecorder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForeground(1, createNotification())
        setupRealtimeSubscription()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun createNotification() = NotificationCompat.Builder(this, "besafe_channel")
        .setContentTitle("BeSafe")
        .setContentText("BeSafe is monitoring for your safety")
        .setSmallIcon(com.be.safe.R.drawable.ic_besafe) // Using ic_besafe.png
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun setupRealtimeSubscription() {
        CoroutineScope(Dispatchers.IO).launch {
            val user = SupabaseClient.client.auth.currentUserOrNull() ?: return@launch
            val channel = SupabaseClient.client.realtime.channel("commands-channel") {}
            val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>("public") {
                table = "commands"
                filter("user_id", FilterOperator.EQ, user.id)
            }
            changeFlow.collectLatest { change ->
                val record = change.record.jsonObject
                val id = record["id"]?.jsonPrimitive?.intOrNull
                val type = record["type"]?.jsonPrimitive?.content ?: return@collectLatest
                val optionsJson = record["options"]?.toString() ?: "{}"
                val options = Json.decodeFromString<Options>(optionsJson)
                val command = Command(id = id, user_id = user.id, type = type, options = options)
                handleCommand(command)
            }
            channel.subscribe()
            SupabaseClient.client.realtime.connect()
        }
    }

    private fun handleCommand(command: Command) {
        when (command.type) {
            "recordAudio" -> recordAudio(command.options.duration)
            "getLocation" -> getLocation()
            "batchLocations" -> batchLocations()
            "ring" -> ringDevice()
            "vibrate" -> vibrateDevice()
        }
    }

    private fun recordAudio(duration: Int?) {
        val outputDir = getOutputDirectory()
        val audioFile = File(outputDir, "audio_${System.currentTimeMillis()}.mp3")
        mediaRecorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(audioFile.absolutePath)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
            start()
        }
        CoroutineScope(Dispatchers.IO).launch {
            delay((duration ?: 60) * 1000L)
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            uploadFile(audioFile, "audio")
        }
    }

    private fun getLocation() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                CoroutineScope(Dispatchers.IO).launch {
                    SupabaseClient.client.postgrest.from("locations").insert(
                        mapOf(
                            "user_id" to SupabaseClient.client.auth.currentUserOrNull()?.id,
                            "latitude" to it.latitude,
                            "longitude" to it.longitude
                        )
                    )
                }
            }
        }
    }

    private fun batchLocations() {
        getLocation()
    }

    private fun ringDevice() {
        val ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        ringtone.play()
        Handler(Looper.getMainLooper()).postDelayed({
            ringtone.stop()
        }, 5000)
    }

    private fun vibrateDevice() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun uploadFile(file: File, type: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val userId = SupabaseClient.client.auth.currentUserOrNull()?.id ?: return@launch
            val path = "$userId/${file.name}"
            SupabaseClient.client.storage.from("files").upload(path, file.readBytes()) {
                upsert = false
            }
            val createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(Date())
            val fileModel = FileModel(
                user_id = userId,
                type = type,
                path = path,
                bucket = "files",
                created_at = createdAt
            )
            SupabaseClient.client.postgrest.from("files").insert(
                Json.encodeToString(fileModel)
            )
        }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "BeSafe").apply { mkdirs() }
        }
        return mediaDir ?: filesDir
      }
}