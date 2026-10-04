package io.github.escossio.andy.features.presence

import android.opengl.EGLContext
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Engine
import io.github.sceneview.SceneView
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.utils.OpenGL
import java.nio.ByteBuffer

/** Replaceable adapter. Only visual values and Compose layout cross this entry point. */
@Composable
fun PresenceStage(
    input: PresenceInput,
    enabled: Boolean,
    modifier: Modifier = Modifier,
): PresenceAvailability {
    val lifetime = remember(enabled) { PresenceLifetime() }
    var availability by remember(enabled) { mutableStateOf(PresenceAvailability.LOADING) }
    val currentFrame by rememberUpdatedState(PresenceReplay.frame(input))
    if (!enabled) return PresenceAvailability.RELEASED
    if (LocalInspectionMode.current) return PresenceAvailability.UNAVAILABLE
    if (availability != PresenceAvailability.LOADING && availability != PresenceAvailability.AVAILABLE) {
        return availability
    }

    AndroidView(
        modifier = modifier,
        factory = { androidContext ->
            val host = FrameLayout(androidContext)
            val resources = PresenceResources()
            var scene: SceneView? = null
            try {
                // Read the one bundled asset directly. Never initialize SceneView's FileLoader.
                val bytes = androidContext.assets.open("presence/andy.glb").use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
                val egl = SceneView.createEglContext().also { resources.egl = it }
                val engine = SceneView.createEngine(egl).also { resources.engine = it }
                val models = ModelLoader(engine, androidContext).also { resources.models = it }
                val materials = MaterialLoader(engine, androidContext).also { resources.materials = it }
                val environments = EnvironmentLoader(engine, androidContext).also { resources.environments = it }
                var framePrepared = false
                scene = object : SceneView(
                    androidContext,
                    sharedEngine = engine,
                    sharedModelLoader = models,
                    sharedMaterialLoader = materials,
                    sharedEnvironmentLoader = environments,
                    cameraManipulator = null,
                    sharedEnvironment = Environment(),
                    isOpaque = false,
                ) {
                    // SceneView's activity hook keeps the entire window awake; this stage does not.
                    override val activity get() = null

                    override fun onFrame(frameTimeNanos: Long) {
                        if (isDestroyed || (lifetime.availability != PresenceAvailability.LOADING &&
                                lifetime.availability != PresenceAvailability.AVAILABLE)) return
                        try {
                            framePrepared = false
                            super.onFrame(frameTimeNanos)
                            if (framePrepared) {
                                lifetime.ready()
                                availability = lifetime.availability
                            }
                        } catch (_: Exception) {
                            lifetime.fail()
                            availability = lifetime.availability
                        } catch (_: LinkageError) {
                            lifetime.fail(unavailable = true)
                            availability = lifetime.availability
                        }
                    }

                    override fun destroy() {
                        if (!isDestroyed) {
                            lifetime.release()
                            availability = lifetime.availability
                            discard {
                                onFrame = null
                                childNodes = emptyList()
                                // ModelLoader owns entities; do not destroy ModelNode entities twice.
                            }
                            discard {
                                try {
                                    super.destroy()
                                } finally {
                                    isDestroyed = true
                                    resources.release()
                                }
                            }
                        }
                    }
                }
                val view = scene
                val instance = view.modelLoader.createModelInstance(buffer) { null }
                check(instance.asset.resourceUris.isEmpty()) { "Embedded resources required" }
                val model = ModelNode(instance, autoAnimate = false)
                val rig = model.nodes.associateBy { it.name }
                val required = setOf("Torso", "Head", "Jaw", "Eyes", "Gaze", "WaveArm", "Jade", "Plum")
                check(rig.keys.containsAll(required)) { "Incomplete visual rig" }
                view.cameraNode.position = Position(0f, 0.15f, 3.8f)
                view.mainLightNode?.lightDirection = Direction(-0.4f, -0.6f, -1f)
                view.childNodes = listOf(model)
                view.onFrame = {
                    val frame = currentFrame
                    rig.getValue("Torso").rotation = Rotation(0f, frame.torsoDegrees, 0f)
                    rig.getValue("Head").rotation = Rotation(frame.gazeY * 3f, frame.headDegrees, 0f)
                    rig.getValue("Jaw").rotation = Rotation(frame.jawDegrees, 0f, 0f)
                    rig.getValue("Eyes").scale = Scale(1f, maxOf(0.025f, frame.eyeOpenness), 1f)
                    rig.getValue("Gaze").position = Position(frame.gazeX * 0.018f, frame.gazeY * 0.014f, 0f)
                    rig.getValue("WaveArm").rotation = Rotation(0f, 0f, frame.armDegrees)
                    rig.getValue("Jade").isVisible = frame.clothing == PresenceClothing.JADE
                    rig.getValue("Plum").isVisible = frame.clothing == PresenceClothing.PLUM
                    framePrepared = true
                }
                host.addView(view, FrameLayout.LayoutParams(-1, -1))
            } catch (_: Exception) {
                releaseScene(scene)
                resources.release()
                // Cleanup releases the old lifetime; availability remains a terminal failure here.
                availability = PresenceAvailability.FAILED
            } catch (_: LinkageError) {
                releaseScene(scene)
                resources.release()
                availability = PresenceAvailability.UNAVAILABLE
            }
            host
        },
        onRelease = { host ->
            for (index in 0 until host.childCount) {
                releaseScene(host.getChildAt(index) as? SceneView)
            }
            host.removeAllViews()
            lifetime.release()
        },
    )
    // Fatal VM errors and native process crashes cannot be recovered here.
    return availability
}

private fun releaseScene(scene: SceneView?) {
    discard { scene?.destroy() }
}

/** Owned before view construction so failed initialization also has a cleanup path. */
private class PresenceResources {
    var egl: EGLContext? = null
    var engine: Engine? = null
    var models: ModelLoader? = null
    var materials: MaterialLoader? = null
    var environments: EnvironmentLoader? = null
    private var released = false

    fun release() {
        if (released) return
        released = true
        discard { models?.destroy() }
        discard { materials?.destroy() }
        // SceneView 2.3.0 MaterialLoader.destroy omits its separate native provider.
        discard { materials?.ubershaderProvider?.destroyMaterials() }
        discard { materials?.ubershaderProvider?.destroy() }
        discard { environments?.destroy() }
        discard { engine?.destroy() }
        discard { OpenGL.destroyEglContext(egl) }
    }
}

private inline fun discard(cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (_: Exception) {
        // Continue remaining cleanup after an ordinary failure, never catch fatal VM errors.
    } catch (_: LinkageError) {
        // A failed class initialization can also make its cleanup entry points unavailable.
    }
}
