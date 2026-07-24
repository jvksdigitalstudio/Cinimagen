package com.jvksdigitalstudio.cinimagen.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.jvksdigitalstudio.cinimagen.engine.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Responsable de convertir URIs (elegidas por el usuario vía Storage
 * Access Framework) en objetos [Layer] con su bitmap decodificado y listo
 * para subir a GPU, o en bitmaps sueltos para reemplazar la imagen de una
 * capa ya existente sin perder sus keyframes.
 */
class LayerRepository(private val context: Context) {

    data class DecodedImage(val bitmap: Bitmap, val displayName: String)

    suspend fun importAsLayers(uris: List<Uri>, startingZIndex: Int): List<Layer> =
        withContext(Dispatchers.IO) {
            uris.mapIndexedNotNull { index, uri ->
                val decoded = decode(uri) ?: return@mapIndexedNotNull null
                Layer(
                    sourceUri = uri,
                    name = decoded.displayName,
                    zIndex = startingZIndex + index,
                    widthPx = decoded.bitmap.width,
                    heightPx = decoded.bitmap.height
                ).apply {
                    pendingBitmap = decoded.bitmap
                }
            }
        }

    /** Decodifica una sola imagen; usado tanto para "importar fondo" como para "reemplazar imagen". */
    suspend fun decode(uri: Uri): DecodedImage? = withContext(Dispatchers.IO) {
        val resolver: ContentResolver = context.contentResolver
        try {
            resolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Algunos proveedores no soportan permisos persistentes; no es fatal.
        }

        val bitmap = ImageDecoding.decodeSampledFromUri(resolver, uri) ?: return@withContext null

        val displayName = queryDisplayName(resolver, uri) ?: "imagen_${System.currentTimeMillis()}"
        DecodedImage(bitmap, displayName)
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }
}
