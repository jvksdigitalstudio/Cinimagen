package com.jvksdigitalstudio.cinimagen.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Decodificación de imágenes con reducción de tamaño (downsampling).
 *
 * Una foto de cámara moderna puede pesar 4000x6000px o más — decodificarla
 * a resolución completa para una capa que como mucho se exporta a 1080x1920
 * es un desperdicio de memoria real, y con varias capas en el mismo
 * proyecto (o al reabrir un proyecto guardado, donde TODAS las capas se
 * decodifican de una) es una causa directa de `OutOfMemoryError` en
 * celulares con poca RAM.
 *
 * [MAX_LAYER_DIMENSION_PX] deja bastante margen por encima de la
 * resolución de exportación por defecto (1080x1920) para permitir zoom
 * sin que se note pixelado, sin cargar el original completo a memoria.
 */
object ImageDecoding {

    const val MAX_LAYER_DIMENSION_PX = 2048

    /** Decodifica un archivo local ya reducido, calculando el inSampleSize correcto de antemano. */
    fun decodeSampledFromFile(file: File, maxDimension: Int = MAX_LAYER_DIMENSION_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /**
     * Decodifica un Uri (típicamente de un picker del sistema) ya reducido.
     * Requiere abrir el stream dos veces (uno para medir, otro para
     * decodificar), ya que los streams de ContentResolver normalmente no
     * se pueden rebobinar.
     */
    fun decodeSampledFromUri(
        resolver: ContentResolver,
        uri: Uri,
        maxDimension: Int = MAX_LAYER_DIMENSION_PX
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // OJO: decodeStream() con inJustDecodeBounds=true SIEMPRE devuelve null
        // (así funciona el modo "solo medir": llena bounds.outWidth/outHeight
        // como efecto secundario, no como valor de retorno). Por eso acá NO se
        // encadena "?: return null" sobre ese resultado — eso cortaría la
        // función en el 100% de los casos, incluso con una imagen perfecta.
        // Lo único que puede fallar de verdad es no conseguir el stream.
        val firstStream = resolver.openInputStream(uri) ?: return null
        firstStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun computeSampleSize(rawWidth: Int, rawHeight: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (rawWidth / sampleSize > maxDimension || rawHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
