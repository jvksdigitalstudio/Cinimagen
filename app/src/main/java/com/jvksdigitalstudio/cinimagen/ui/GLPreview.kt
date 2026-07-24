package com.jvksdigitalstudio.cinimagen.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.jvksdigitalstudio.cinimagen.engine.CameraFrame
import com.jvksdigitalstudio.cinimagen.engine.GLRenderer
import com.jvksdigitalstudio.cinimagen.engine.Layer

/**
 * Puente entre Compose y el GLSurfaceView clásico de Android.
 *
 * IMPORTANTE: el `factory` de AndroidView se ejecuta UNA sola vez (crea la
 * vista y el renderer una única vez, no en cada recomposición). Si le
 * pasáramos las lambdas recibidas directamente, el GLRenderer quedaría
 * atado para siempre a los valores que existían en el instante exacto de
 * la primera composición. La solución estándar de Compose es
 * [rememberUpdatedState]: envolvemos cada lambda en un State que SÍ se
 * actualiza en cada recomposición, y le pasamos al renderer una lambda
 * estable que simplemente lee `.value` en cada frame.
 */
@Composable
fun GLPreview(
    modifier: Modifier = Modifier,
    getLayers: () -> List<Layer>,
    getPlayheadMs: () -> Long,
    getLiveOverride: () -> Pair<String, CameraFrame>? = { null }
) {
    val currentGetLayers by rememberUpdatedState(getLayers)
    val currentGetPlayheadMs by rememberUpdatedState(getPlayheadMs)
    val currentGetLiveOverride by rememberUpdatedState(getLiveOverride)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                setZOrderOnTop(false)
                val renderer = GLRenderer(
                    contentResolver = context.contentResolver,
                    getLayers = { currentGetLayers() },
                    getPlayheadMs = { currentGetPlayheadMs() },
                    getLiveOverride = { currentGetLiveOverride() }
                )
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
    )
}
