package io.github.escossio.andy

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.escossio.andy.core.deviceidentity.DeviceIdentityResult
import io.github.escossio.andy.data.deviceidentity.AndroidDeviceIdentityFactory

internal const val BOOTSTRAP_TEXT = "Andy"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeDeviceIdentity()
        setContent {
            AndyBootstrap()
        }
    }

    private fun initializeDeviceIdentity() {
        when (val result = AndroidDeviceIdentityFactory.create(applicationContext).ensureIdentity()) {
            is DeviceIdentityResult.Ready -> Log.i(LOG_TAG, "DEVICE_IDENTITY_READY")
            is DeviceIdentityResult.Unavailable ->
                Log.w(LOG_TAG, "DEVICE_IDENTITY_UNAVAILABLE:${result.reason.name}")
        }
    }

    private companion object {
        const val LOG_TAG = "AndyDeviceIdentity"
    }
}

@Composable
private fun AndyBootstrap() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(BOOTSTRAP_TEXT)
    }
}
