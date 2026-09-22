package io.github.escossio.andy.features.onboarding

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sin

data class AndyPresenceMotion(
    val horizontal: Float = 0f,
    val vertical: Float = 0f,
)

fun andyPresenceMotionFromOrientation(
    pitchDeltaRad: Float,
    rollDeltaRad: Float,
): AndyPresenceMotion = AndyPresenceMotion(
    horizontal = (rollDeltaRad / 0.42f).coerceIn(-1f, 1f),
    vertical = (-pitchDeltaRad / 0.42f).coerceIn(-1f, 1f),
)

@Composable
internal fun AndyPresenceAvatar(
    modifier: Modifier = Modifier,
    speechAmplitude: Float = 0f,
) {
    val context = LocalContext.current
    var motion by remember { mutableStateOf(AndyPresenceMotion()) }

    DisposableEffect(context) {
        val sensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val selectedSensor = rotationSensor ?: accelerometer
        var baselinePitch: Float? = null
        var baselineRoll: Float? = null
        var baselineAx: Float? = null
        var baselineAy: Float? = null

        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent) {
                val target = if (
                    event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
                    event.sensor.type == Sensor.TYPE_ROTATION_VECTOR
                ) {
                    val matrix = FloatArray(9)
                    val orientation = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    SensorManager.getOrientation(matrix, orientation)
                    val pitch = orientation[1]
                    val roll = orientation[2]
                    val basePitch = baselinePitch
                    val baseRoll = baselineRoll
                    if (basePitch == null || baseRoll == null) {
                        baselinePitch = pitch
                        baselineRoll = roll
                        return
                    }
                    andyPresenceMotionFromOrientation(
                        pitchDeltaRad = pitch - basePitch,
                        rollDeltaRad = roll - baseRoll,
                    )
                } else {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val baseAx = baselineAx
                    val baseAy = baselineAy
                    if (baseAx == null || baseAy == null) {
                        baselineAx = ax
                        baselineAy = ay
                        return
                    }
                    AndyPresenceMotion(
                        horizontal = ((ax - baseAx) / 3.2f).coerceIn(-1f, 1f),
                        vertical = ((ay - baseAy) / 3.2f).coerceIn(-1f, 1f),
                    )
                }
                motion = AndyPresenceMotion(
                    horizontal = motion.horizontal * 0.84f +
                        target.horizontal * 0.16f,
                    vertical = motion.vertical * 0.84f +
                        target.vertical * 0.16f,
                )
            }
        }

        selectedSensor?.let {
            sensorManager.registerListener(
                listener,
                it,
                SensorManager.SENSOR_DELAY_GAME,
            )
        }

        onDispose {
            sensorManager.unregisterListener(listener)
            motion = AndyPresenceMotion()
        }
    }

    var frameNanos by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos = it }
        }
    }

    val seconds = frameNanos / 1_000_000_000f
    val idleHorizontal = sin(seconds * 0.47f) * 0.32f
    val idleVertical = sin(seconds * 0.73f + 0.8f) * 0.22f
    val breathing = sin(seconds * 1.15f) * 1.6f
    val blinkPosition = (seconds + 0.4f) % 4.7f
    val eyeOpen = when {
        blinkPosition < 0.07f -> 1f - blinkPosition / 0.07f
        blinkPosition < 0.14f -> (blinkPosition - 0.07f) / 0.07f
        else -> 1f
    }.coerceIn(0.08f, 1f)

    val gazeX =
        (motion.horizontal * 0.68f + idleHorizontal * 0.32f)
            .coerceIn(-1f, 1f)
    val gazeY =
        (motion.vertical * 0.45f + idleVertical * 0.28f)
            .coerceIn(-0.65f, 0.65f)
    val mouthOpen = speechAmplitude.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        val w = size.width
        val h = size.height
        val shiftX = motion.horizontal * w * 0.012f
        val shiftY = motion.vertical * h * 0.008f + breathing
        val rotation = motion.horizontal * 3f

        drawRoundRect(
            color = Color(0xFF183128),
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
        )
        drawCircle(
            color = Color(0xFF2D6552),
            radius = w * 0.24f,
            center = Offset(w * 0.50f, h * 0.38f),
        )
        drawCircle(
            color = Color(0xFF3F8069).copy(alpha = 0.48f),
            radius = w * 0.33f,
            center = Offset(w * 0.50f, h * 0.40f),
        )
        drawRoundRect(
            color = Color(0xFF8B6243),
            topLeft = Offset(w * 0.08f, h * 0.78f),
            size = Size(w * 0.84f, h * 0.12f),
            cornerRadius = CornerRadius(w * 0.025f, w * 0.025f),
        )

        rotate(
            degrees = rotation,
            pivot = Offset(w * 0.50f, h * 0.47f),
        ) {
            translate(left = shiftX, top = shiftY) {
                val body = Path().apply {
                    moveTo(w * 0.30f, h * 0.78f)
                    cubicTo(
                        w * 0.31f, h * 0.61f,
                        w * 0.38f, h * 0.55f,
                        w * 0.50f, h * 0.55f,
                    )
                    cubicTo(
                        w * 0.63f, h * 0.55f,
                        w * 0.70f, h * 0.62f,
                        w * 0.72f, h * 0.78f,
                    )
                    close()
                }
                drawPath(body, Color(0xFF202925))

                drawOval(
                    color = Color(0xFF2A1A18),
                    topLeft = Offset(w * 0.34f, h * 0.19f),
                    size = Size(w * 0.32f, h * 0.42f),
                )
                drawOval(
                    color = Color(0xFFF0BE9D),
                    topLeft = Offset(w * 0.365f, h * 0.245f),
                    size = Size(w * 0.27f, h * 0.31f),
                )

                val hair = Path().apply {
                    moveTo(w * 0.36f, h * 0.34f)
                    cubicTo(
                        w * 0.34f, h * 0.21f,
                        w * 0.43f, h * 0.17f,
                        w * 0.50f, h * 0.18f,
                    )
                    cubicTo(
                        w * 0.64f, h * 0.17f,
                        w * 0.66f, h * 0.28f,
                        w * 0.64f, h * 0.36f,
                    )
                    cubicTo(
                        w * 0.61f, h * 0.30f,
                        w * 0.57f, h * 0.27f,
                        w * 0.51f, h * 0.26f,
                    )
                    cubicTo(
                        w * 0.45f, h * 0.25f,
                        w * 0.40f, h * 0.28f,
                        w * 0.36f, h * 0.34f,
                    )
                    close()
                }
                drawPath(hair, Color(0xFF35201D))
                drawCircle(
                    color = Color(0xFF2A1A18),
                    radius = w * 0.085f,
                    center = Offset(w * 0.53f, h * 0.18f),
                )

                val pupilX = gazeX * w * 0.010f
                val pupilY = gazeY * h * 0.006f
                val eyeHeight = h * 0.030f * eyeOpen

                fun drawEye(cx: Float) {
                    drawOval(
                        color = Color(0xFFFFFAF5),
                        topLeft = Offset(
                            cx - w * 0.040f,
                            h * 0.365f - eyeHeight / 2f,
                        ),
                        size = Size(w * 0.080f, eyeHeight),
                    )
                    if (eyeOpen > 0.28f) {
                        drawCircle(
                            color = Color(0xFF4D3429),
                            radius = w * 0.012f,
                            center = Offset(
                                cx + pupilX,
                                h * 0.365f + pupilY,
                            ),
                        )
                        drawCircle(
                            color = Color(0xFF151A18),
                            radius = w * 0.006f,
                            center = Offset(
                                cx + pupilX,
                                h * 0.365f + pupilY,
                            ),
                        )
                    }
                }

                drawEye(w * 0.445f)
                drawEye(w * 0.555f)

                val smile = Path().apply {
                    moveTo(w * 0.455f, h * 0.465f)
                    cubicTo(
                        w * 0.48f, h * (0.485f + mouthOpen * 0.006f),
                        w * 0.52f, h * (0.485f + mouthOpen * 0.006f),
                        w * 0.548f, h * 0.462f,
                    )
                }
                drawPath(
                    smile,
                    Color(0xFFA94F59),
                    style = Stroke(width = w * 0.008f),
                )
                if (mouthOpen > 0.08f) {
                    drawOval(
                        color = Color(0xFF8B3E4A),
                        topLeft = Offset(w * 0.482f, h * 0.467f),
                        size = Size(
                            w * 0.038f,
                            h * 0.010f * mouthOpen,
                        ),
                    )
                }

                drawCircle(
                    color = Color(0xFFF0BE9D),
                    radius = w * 0.036f,
                    center = Offset(w * 0.31f, h * 0.735f),
                )
                drawCircle(
                    color = Color(0xFFF0BE9D),
                    radius = w * 0.034f,
                    center = Offset(
                        w * 0.68f + idleHorizontal * w * 0.006f,
                        h * 0.745f,
                    ),
                )
                drawRoundRect(
                    color = Color(0xFF39413F),
                    topLeft = Offset(w * 0.56f, h * 0.655f),
                    size = Size(w * 0.27f, h * 0.15f),
                    cornerRadius = CornerRadius(w * 0.018f, w * 0.018f),
                )
                drawRoundRect(
                    color = Color(0xFF59625F),
                    topLeft = Offset(w * 0.53f, h * 0.805f),
                    size = Size(w * 0.34f, h * 0.025f),
                    cornerRadius = CornerRadius(w * 0.01f, w * 0.01f),
                )
            }
        }

        drawCircle(
            color = Color(0xFFE2F5ED).copy(alpha = 0.70f),
            radius = w * 0.012f,
            center = Offset(w * 0.91f, h * 0.10f),
        )
        drawCircle(
            color = Color(0xFF7BC6A6).copy(alpha = 0.75f),
            radius = w * 0.007f,
            center = Offset(w * 0.88f, h * 0.14f),
        )
    }
}
