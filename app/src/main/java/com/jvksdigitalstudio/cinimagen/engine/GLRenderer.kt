package com.jvksdigitalstudio.cinimagen.engine

import android.content.ContentResolver
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.jvksdigitalstudio.cinimagen.data.ImageDecoding

/**
 * Renderer del preview en vivo. Toda la lógica real de dibujo vive en
 * [LayerDrawer] (compartida con el exportador de video offline); aquí solo
 * se resuelve el ciclo de vida GLSurfaceView y la subida perezosa de
 * texturas desde los bitmaps pendientes de cada capa.
 *
 * El look cinematográfico (grading, viñeta, grano, glow) es propio de
 * cada [Layer] — se lee directo de `layer.lookSettings`, no hay un ajuste
 * global compartido entre capas.
 */
class GLRenderer(
    private val contentResolver: ContentResolver,
    private val getLayers: () -> List<Layer>,
    private val getPlayheadMs: () -> Long,
    private val getLiveOverride: () -> Pair<String, CameraFrame>? = { null }
) : GLSurfaceView.Renderer {

    private val drawer = LayerDrawer()
    private var viewportWidth: Int = 1
    private var viewportHeight: Int = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        drawer.ensureInitialized()

        // IMPORTANTE: esto se llama cada vez que se crea un contexto EGL
        // nuevo — no solo la primera vez. Eso pasa, por ejemplo, al volver
        // a "Mis proyectos" y reabrir el mismo proyecto: el ViewModel (y
        // sus capas) se reutiliza, pero el GLSurfaceView y su contexto GL
        // se destruyen y se recrean desde cero. Cualquier `glTextureId` ya
        // asignado pertenece al contexto VIEJO (ya destruido) y ya no es
        // válido en este — usarlo tal cual dejaba el preview en negro. Acá
        // se invalida esa textura y, si el bitmap en memoria ya se había
        // liberado (caso normal: se libera apenas se sube a GL), se
        // vuelve a decodificar desde la copia local de la capa para
        // poder subirla de nuevo.
        for (layer in getLayers()) {
            layer.glTextureId = -1
            if (layer.pendingBitmap == null) {
                layer.pendingBitmap = runCatching {
                    ImageDecoding.decodeSampledFromUri(contentResolver, layer.sourceUri)
                }.getOrNull()
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        drawer.clear()

        val layers = getLayers().sortedBy { it.zIndex }
        val timeMs = getPlayheadMs()
        val override = getLiveOverride()

        for (layer in layers) {
            uploadTextureIfNeeded(layer)
            if (layer.glTextureId < 0 || !layer.visible) continue
            val frame = if (override != null && override.first == layer.id) {
                override.second
            } else {
                layer.cameraTrack.frameAt(timeMs)
            }
            // Frame de ~33ms atrás, solo para calcular el vector de
            // movimiento del motion blur — no se usa si la capa no tiene
            // motion blur activado (drawLayer lo ignora en ese caso).
            val previousFrame = if (layer.lookSettings.motionBlurIntensity > 0.001f) {
                layer.cameraTrack.frameAt((timeMs - 33L).coerceAtLeast(0L))
            } else null
            drawer.drawLayer(
                textureId = layer.glTextureId,
                imageWidthPx = layer.widthPx,
                imageHeightPx = layer.heightPx,
                frame = frame,
                parallaxFactor = layer.parallaxFactor,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                look = layer.lookSettings,
                timeSeconds = timeMs / 1000f,
                previousFrame = previousFrame
            )
        }
    }

    private fun uploadTextureIfNeeded(layer: Layer) {
        val bitmap = layer.pendingBitmap ?: return
        if (layer.glTextureId >= 0) {
            // Ya había una textura (caso "reemplazar imagen"): liberarla antes de subir la nueva.
            drawer.deleteTexture(layer.glTextureId)
        }
        layer.glTextureId = drawer.uploadTexture(bitmap)
        layer.widthPx = bitmap.width
        layer.heightPx = bitmap.height
        bitmap.recycle()
        layer.pendingBitmap = null
    }
}
