package com.yeivikasdigitalstudio.olyze.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extrusión 3D real de una capa (foto, PNG recortado, forma, texto ya
 * rasterizado — cualquier bitmap con canal alpha sirve) — el equivalente
 * de la herramienta "3D" de apps de diseño tipo Canva/CapCut: el
 * contorno del recorte se convierte en un cuerpo con espesor (tapa
 * frontal, tapa trasera y paredes laterales con bisel redondeado), se
 * rota en X/Y/Z y se proyecta con una perspectiva suave, todo sombreado
 * con un modelo de luz simple (Lambert + piso ambiental) para que se
 * lea como un objeto real y no como una imagen inclinada.
 *
 * Igual que [ColorExtraction] (que reutiliza para el color del
 * material), esto es CPU/Canvas puro — no toca el pipeline GL ni el
 * exportador de video: el resultado es un bitmap nuevo que el panel de
 * edición (ver EditorScreen.Extrude3DPanel) sube como vista previa en
 * vivo y, 500ms después de la última interacción, persiste con
 * EditorViewModel.commitLayerRecolor — exactamente el mismo mecanismo
 * que el panel "Recolor", así que el efecto "horneado" sobrevive a
 * guardar/exportar sin que el resto del motor tenga que saber que esto
 * existe.
 *
 * Simplificaciones deliberadas, documentadas para no prometer de más:
 *  - El contorno se extrae por marching squares sobre el canal alpha
 *    (funciona con formas, texto con agujeros como la "O", o varias
 *    letras sueltas — cada una sale como su propio lazo), pero es un
 *    contorno POLIGONAL simplificado, no una curva suave — con el bisel
 *    subdividido en varios pasos el resultado se ve redondeado igual.
 *  - Las rotaciones se limitan a ±60° en X/Y a propósito: dentro de ese
 *    rango la tapa frontal siempre queda mirando hacia la cámara, así
 *    que el orden de dibujo (tapa trasera → paredes → tapa frontal)
 *    es siempre correcto sin tener que ordenar cada triángulo de las
 *    tapas contra cada pared una por una.
 *  - El material de las paredes es un color sólido (el dominante de la
 *    imagen, ColorExtraction.dominantColor) sombreado por cara, no la
 *    foto "envuelta" en el canto — así es como lo hacen la mayoría de
 *    estas herramientas de extrusión de bajo poligonaje también.
 */
object Extrude3D {

    /** Parámetros del efecto, uno a uno con los sliders del panel. */
    data class Params(
        val rotationXDeg: Float = 0f,
        val rotationYDeg: Float = 0f,
        val rotationZDeg: Float = 0f,
        /** 0f..1f, relativo al tamaño propio de la forma. */
        val depth: Float = 0.35f,
        /** 0f..1f, cuánto se redondea el canto. */
        val bevel: Float = 0.5f,
        /** 0f..1f, opacidad general del cuerpo extruido (tapas y paredes). */
        val opacity: Float = 1f
    )

    private const val BEVEL_STEPS = 4
    private const val CAP_GRID = 22
    private const val MASK_MAX_SIDE = 180

    fun render(source: Bitmap, params: Params): Bitmap {
        val srcW = source.width
        val srcH = source.height
        if (srcW <= 0 || srcH <= 0) return source

        val refRadius = max(srcW, srcH) / 2f
        val halfDepth = params.depth.coerceIn(0f, 1f) * refRadius * 0.9f
        val bevelAmount = params.bevel.coerceIn(0f, 1f) * refRadius * 0.22f
        val opacity = params.opacity.coerceIn(0f, 1f)

        val rx = Math.toRadians(params.rotationXDeg.coerceIn(-60f, 60f).toDouble())
        val ry = Math.toRadians(params.rotationYDeg.coerceIn(-60f, 60f).toDouble())
        val rz = Math.toRadians(params.rotationZDeg.coerceIn(-180f, 180f).toDouble())
        val rot = Rot3(rx, ry, rz)

        val camDist = refRadius * 4.2
        fun project(p: DoubleArray): FloatArray {
            val f = (camDist / (camDist - p[2])).toFloat()
            return floatArrayOf(p[0].toFloat() * f, p[1].toFloat() * f)
        }

        val margin = (refRadius * 1.35f).toInt().coerceAtLeast(4)
        val outW = srcW + margin * 2
        val outH = srcH + margin * 2
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val originX = outW / 2f
        val originY = outH / 2f

        val lightDir = normalize3(doubleArrayOf(-0.35, -0.55, 0.76))
        val capNormal = rot.apply(doubleArrayOf(0.0, 0.0, 1.0))
        val frontFacesCamera = capNormal[2] >= 0.0
        val frontShade = (0.22 + 0.78 * dot3(capNormal, lightDir).coerceIn(0.0, 1.0)).toFloat()
        val backNormal = doubleArrayOf(-capNormal[0], -capNormal[1], -capNormal[2])
        val backShade = (0.22 + 0.78 * dot3(backNormal, lightDir).coerceIn(0.0, 1.0)).toFloat()

        fun drawCap(zLocal: Float, shade: Float, alpha255: Int) {
            drawCapMesh(canvas, source, srcW, srcH, zLocal, rot, ::project, originX, originY, shade, alpha255)
        }

        val bodyAlpha255 = (opacity * 255).roundToInt().coerceIn(0, 255)

        // Tapa que queda atrás primero (pintor: fondo -> frente).
        if (frontFacesCamera) drawCap(-halfDepth, backShade, bodyAlpha255)
        else drawCap(halfDepth, frontShade, bodyAlpha255)

        val loopsPx = traceAlphaBoundaries(source)
        drawWalls(
            canvas, loopsPx, srcW, srcH, halfDepth, bevelAmount, rot, ::project,
            originX, originY, lightDir, ColorExtraction.dominantColor(source), opacity,
            outW, outH
        )

        // Tapa visible al final, siempre encima.
        if (frontFacesCamera) drawCap(halfDepth, frontShade, bodyAlpha255)
        else drawCap(-halfDepth, backShade, bodyAlpha255)

        return result
    }

    // ---------------------------------------------------------------
    // Tapas: se dibuja el bitmap ORIGINAL completo (con su alpha tal
    // cual) sobre una grilla deformada por la rotación/proyección — así
    // no hace falta triangular el relleno del contorno para nada, sirve
    // igual para fotos, PNGs recortados o texto rasterizado.
    // ---------------------------------------------------------------
    private fun drawCapMesh(
        canvas: Canvas,
        bitmap: Bitmap,
        srcW: Int,
        srcH: Int,
        zLocal: Float,
        rot: Rot3,
        project: (DoubleArray) -> FloatArray,
        originX: Float,
        originY: Float,
        shade: Float,
        alpha255: Int
    ) {
        val cols = CAP_GRID
        val rows = CAP_GRID
        val verts = FloatArray((cols + 1) * (rows + 1) * 2)
        var idx = 0
        val halfW = srcW / 2.0
        val halfH = srcH / 2.0
        for (gy in 0..rows) {
            val v = gy.toDouble() / rows
            val y = -halfH + v * srcH
            for (gx in 0..cols) {
                val u = gx.toDouble() / cols
                val x = -halfW + u * srcW
                val p = project(rot.apply(doubleArrayOf(x, y, zLocal.toDouble())))
                verts[idx++] = originX + p[0]
                verts[idx++] = originY + p[1]
            }
        }
        val tint = (shade.coerceIn(0f, 1f) * 255).roundToInt().coerceIn(0, 255)
        val vertexColor = AColor.argb(255, tint, tint, tint)
        val colors = IntArray((cols + 1) * (rows + 1)) { vertexColor }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = alpha255 }
        canvas.drawBitmapMesh(bitmap, cols, rows, verts, 0, colors, 0, paint)
    }

    // ---------------------------------------------------------------
    // Paredes: cinta de cuadriláteros siguiendo cada lazo del contorno,
    // con un perfil de bisel (cuarto de círculo en cada punta, plano en
    // el medio) para que el canto se lea redondeado en vez de un corte
    // filoso de caja.
    // ---------------------------------------------------------------
    private data class Band(val z: Float, val outward: Float)

    private fun bevelBands(halfDepth: Float, bevelAmount: Float): List<Band> {
        // El "outward" se calcula sobre `span` (ya recortado por la
        // profundidad disponible), no sobre bevelAmount crudo. Antes el
        // bisel podía pedir más vuelo lateral del que el espesor
        // permitía (p. ej. profundidad baja + bisel 100%), generando
        // un canto desproporcionadamente ancho que se comía a sí mismo
        // en las esquinas.
        val span = min(bevelAmount, halfDepth * 0.9f).coerceAtLeast(0.001f)
        val bands = ArrayList<Band>()
        for (i in 0..BEVEL_STEPS) {
            val angle = (i.toFloat() / BEVEL_STEPS) * (Math.PI / 2)
            val outward = (span * sin(angle)).toFloat()
            val zDrop = (span * (1 - cos(angle))).toFloat()
            bands.add(Band(halfDepth - zDrop, outward))
        }
        val midFront = halfDepth - span
        val midBack = -(halfDepth - span)
        if (midFront > midBack + 0.01f) bands.add(Band(midBack, span))
        for (i in BEVEL_STEPS downTo 0) {
            val angle = (i.toFloat() / BEVEL_STEPS) * (Math.PI / 2)
            val outward = (span * sin(angle)).toFloat()
            val zDrop = (span * (1 - cos(angle))).toFloat()
            bands.add(Band(-(halfDepth - zDrop), outward))
        }
        return bands
    }

    // ---------------------------------------------------------------
    // Offset de polígono con join por vértice (miter, con límite) —
    // en vez de inflar cada arista por separado (lo que dejaba de
    // coincidir en cualquier esquina cóncava o filosa y se
    // autointersectaba, el "ovillo" que se veía en formas reales),
    // cada vértice se desplaza en la dirección promedio (bisectriz)
    // de sus dos aristas vecinas, con la distancia ajustada según el
    // ángulo entre ellas para que el offset siga siendo perpendicular
    // y consistente a ambos lados. El límite de miter evita picos
    // infinitos en esquinas casi en punta (típico de contornos con
    // ruido): más allá del límite se recorta, similar a un bevel join.
    // ---------------------------------------------------------------
    private fun offsetPolygon(loop: List<PointF>, distance: Float): List<PointF> {
        if (distance <= 0.0001f) return loop
        val n = loop.size
        val miterLimit = distance * 4f
        val result = ArrayList<PointF>(n)
        for (i in 0 until n) {
            val prev = loop[(i - 1 + n) % n]
            val curr = loop[i]
            val next = loop[(i + 1) % n]

            val d0x = curr.x - prev.x; val d0y = curr.y - prev.y
            val len0 = hypot(d0x.toDouble(), d0y.toDouble()).toFloat().let { if (it < 1e-4f) 1f else it }
            val n0x = d0y / len0; val n0y = -d0x / len0

            val d1x = next.x - curr.x; val d1y = next.y - curr.y
            val len1 = hypot(d1x.toDouble(), d1y.toDouble()).toFloat().let { if (it < 1e-4f) 1f else it }
            val n1x = d1y / len1; val n1y = -d1x / len1

            var mx = n0x + n1x; var my = n0y + n1y
            val mlen = hypot(mx.toDouble(), my.toDouble()).toFloat()
            if (mlen < 1e-4f) {
                // Aristas casi opuestas (pico de ~180°): usar solo n0.
                result.add(PointF(curr.x + n0x * distance, curr.y + n0y * distance))
                continue
            }
            mx /= mlen; my /= mlen
            val cosHalf = (mx * n0x + my * n0y).coerceIn(0.15f, 1f)
            val miterLen = min(distance / cosHalf, miterLimit)
            result.add(PointF(curr.x + mx * miterLen, curr.y + my * miterLen))
        }
        return result
    }

    // Suaviza el contorno (Chaikin, corta esquinas) antes de generar
    // las paredes — el trazador por marching squares deja micro
    // escalones en diagonales (más notorio en fotos con borde
    // antialiased); sin este paso esos escalones se vuelven esquinas
    // filosas de más para el offset con miter de arriba.
    private fun smoothLoop(points: List<PointF>): List<PointF> {
        if (points.size < 4) return points
        val n = points.size
        val out = ArrayList<PointF>(n * 2)
        for (i in 0 until n) {
            val p0 = points[i]
            val p1 = points[(i + 1) % n]
            out.add(PointF(p0.x * 0.75f + p1.x * 0.25f, p0.y * 0.75f + p1.y * 0.25f))
            out.add(PointF(p0.x * 0.25f + p1.x * 0.75f, p0.y * 0.25f + p1.y * 0.75f))
        }
        return out
    }

    // Un triángulo de pared ya proyectado a pantalla: (x,y) por vértice
    // más su profundidad de cámara (post-rotación, pre-proyección) para
    // el test de z-buffer, y un color plano (ya sombreado) para toda
    // la cara. z mayor = más cerca de la cámara (ver `project`).
    private class WallTri(
        val x0: Float, val y0: Float, val z0: Float,
        val x1: Float, val y1: Float, val z1: Float,
        val x2: Float, val y2: Float, val z2: Float,
        val color: Int
    )

    private fun drawWalls(
        canvas: Canvas,
        loopsPx: List<List<PointF>>,
        srcW: Int,
        srcH: Int,
        halfDepth: Float,
        bevelAmount: Float,
        rot: Rot3,
        project: (DoubleArray) -> FloatArray,
        originX: Float,
        originY: Float,
        lightDir: DoubleArray,
        materialColor: Int,
        opacity: Float,
        outW: Int,
        outH: Int
    ) {
        if (loopsPx.isEmpty() || halfDepth <= 0.01f) return
        val halfW = srcW / 2f
        val halfH = srcH / 2f
        val bands = bevelBands(halfDepth, bevelAmount)
        val mr = AColor.red(materialColor) / 255f
        val mg = AColor.green(materialColor) / 255f
        val mb = AColor.blue(materialColor) / 255f
        val alpha255 = (opacity * 255).roundToInt().coerceIn(0, 255)

        val tris = ArrayList<WallTri>()

        for (rawLoop in loopsPx) {
            if (rawLoop.size < 3) continue
            val loop = smoothLoop(rawLoop)
            val n = loop.size
            val lx = loop.map { it.x - halfW }
            val ly = loop.map { it.y - halfH }
            val flatLoop = List(n) { PointF(lx[it], ly[it]) }

            // Offset con join calculado UNA vez por banda para todo el
            // lazo (no por arista) — así los vértices compartidos entre
            // aristas vecinas coinciden exactamente y no se cruzan.
            val bandPolys = bands.map { b -> offsetPolygon(flatLoop, b.outward) }

            for (e in 0 until n) {
                val e2 = (e + 1) % n
                for (bi in 0 until bands.size - 1) {
                    val b0 = bands[bi]; val b1 = bands[bi + 1]
                    val p00 = bandPolys[bi][e]; val p01 = bandPolys[bi][e2]
                    val p11 = bandPolys[bi + 1][e2]; val p10 = bandPolys[bi + 1][e]

                    val v00 = doubleArrayOf(p00.x.toDouble(), p00.y.toDouble(), b0.z.toDouble())
                    val v01 = doubleArrayOf(p01.x.toDouble(), p01.y.toDouble(), b0.z.toDouble())
                    val v11 = doubleArrayOf(p11.x.toDouble(), p11.y.toDouble(), b1.z.toDouble())
                    val v10 = doubleArrayOf(p10.x.toDouble(), p10.y.toDouble(), b1.z.toDouble())

                    val e1v = doubleArrayOf(v01[0] - v00[0], v01[1] - v00[1], v01[2] - v00[2])
                    val e2v = doubleArrayOf(v10[0] - v00[0], v10[1] - v00[1], v10[2] - v00[2])
                    val localNormal = normalize3(cross3(e1v, e2v))

                    val rv00 = rot.apply(v00); val rv01 = rot.apply(v01)
                    val rv11 = rot.apply(v11); val rv10 = rot.apply(v10)
                    val rn = normalize3(rot.apply(localNormal))

                    val pp00 = project(rv00); val pp01 = project(rv01)
                    val pp11 = project(rv11); val pp10 = project(rv10)

                    val shade = (0.18 + 0.82 * dot3(rn, lightDir).coerceIn(0.0, 1.0)).toFloat()
                    val r = (mr * shade * 255).roundToInt().coerceIn(0, 255)
                    val g = (mg * shade * 255).roundToInt().coerceIn(0, 255)
                    val bl = (mb * shade * 255).roundToInt().coerceIn(0, 255)
                    val color = AColor.argb(alpha255, r, g, bl)

                    val x00 = originX + pp00[0]; val y00 = originY + pp00[1]
                    val x01 = originX + pp01[0]; val y01 = originY + pp01[1]
                    val x11 = originX + pp11[0]; val y11 = originY + pp11[1]
                    val x10 = originX + pp10[0]; val y10 = originY + pp10[1]

                    // Mismo cuadrilátero que antes (v00->v01->v11->v10),
                    // partido en 2 triángulos para el rasterizador.
                    tris.add(WallTri(x00, y00, rv00[2].toFloat(), x01, y01, rv01[2].toFloat(), x11, y11, rv11[2].toFloat(), color))
                    tris.add(WallTri(x00, y00, rv00[2].toFloat(), x11, y11, rv11[2].toFloat(), x10, y10, rv10[2].toFloat(), color))
                }
            }
        }
        if (tris.isEmpty()) return

        // Z-buffer real por píxel en vez de "ordenar por profundidad
        // promedio y pintar" (algoritmo del pintor): con rotaciones
        // fuertes las tiras del canto quedan casi de canto respecto a
        // la cámara y sus profundidades promedio se entrelazan, así
        // que el orden por promedio deja de ser válido y el resultado
        // era un enrejado de tiras cruzadas en vez de un tubo sólido.
        // Comparando la profundidad real de cada píxel contra las
        // demás caras siempre se pinta la que está más cerca de la
        // cámara, sin importar el ángulo.
        val pixels = IntArray(outW * outH)
        val depthBuf = FloatArray(outW * outH) { Float.NEGATIVE_INFINITY }
        for (t in tris) rasterizeWallTri(t, pixels, depthBuf, outW, outH)

        val wallsBitmap = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(wallsBitmap, 0f, 0f, null)
        wallsBitmap.recycle()
    }

    private fun rasterizeWallTri(t: WallTri, pixels: IntArray, depthBuf: FloatArray, w: Int, h: Int) {
        val minX = max(0, floor(min(t.x0, min(t.x1, t.x2))).toInt())
        val maxX = min(w - 1, ceil(max(t.x0, max(t.x1, t.x2))).toInt())
        val minY = max(0, floor(min(t.y0, min(t.y1, t.y2))).toInt())
        val maxY = min(h - 1, ceil(max(t.y0, max(t.y1, t.y2))).toInt())
        if (minX > maxX || minY > maxY) return

        val area = (t.x1 - t.x0) * (t.y2 - t.y0) - (t.x2 - t.x0) * (t.y1 - t.y0)
        if (abs(area) < 1e-4f) return
        val invArea = 1f / area

        for (py in minY..maxY) {
            val cy = py + 0.5f
            var rowBase = py * w
            for (px in minX..maxX) {
                val cx = px + 0.5f
                // Coordenadas baricéntricas del punto (cx,cy).
                var w0 = ((t.x1 - cx) * (t.y2 - cy) - (t.x2 - cx) * (t.y1 - cy)) * invArea
                var w1 = ((t.x2 - cx) * (t.y0 - cy) - (t.x0 - cx) * (t.y2 - cy)) * invArea
                var w2 = 1f - w0 - w1
                if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue
                val z = w0 * t.z0 + w1 * t.z1 + w2 * t.z2
                val i = rowBase + px
                if (z > depthBuf[i]) {
                    depthBuf[i] = z
                    pixels[i] = t.color
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Contorno del canal alpha por marching squares — devuelve uno o
    // más lazos cerrados en coordenadas de píxel de `source`. Sirve
    // igual para una forma sólida, una foto recortada, o texto (cada
    // letra/agujero sale como su propio lazo, sin lógica especial).
    // ---------------------------------------------------------------
    private fun traceAlphaBoundaries(source: Bitmap): List<List<PointF>> {
        val srcW = source.width
        val srcH = source.height
        val scale = MASK_MAX_SIDE.toFloat() / max(srcW, srcH)
        val useScale = scale < 1f
        val mw = if (useScale) max(1, (srcW * scale).roundToInt()) else srcW
        val mh = if (useScale) max(1, (srcH * scale).roundToInt()) else srcH
        val sampled = if (useScale) {
            runCatching { Bitmap.createScaledBitmap(source, mw, mh, true) }.getOrNull() ?: source
        } else source

        val pixels = IntArray(mw * mh)
        sampled.getPixels(pixels, 0, mw, 0, 0, mw, mh)
        fun opaque(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= mw || y >= mh) return false
            return ((pixels[y * mw + x] ushr 24) and 0xFF) >= 24
        }

        data class Seg(val a: PointF, val b: PointF)
        val segs = ArrayList<Seg>()
        for (cy in -1 until mh) {
            for (cx in -1 until mw) {
                val tl = opaque(cx, cy)
                val tr = opaque(cx + 1, cy)
                val br = opaque(cx + 1, cy + 1)
                val bl = opaque(cx, cy + 1)
                val code = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
                if (code == 0 || code == 15) continue
                val top = PointF(cx + 0.5f, cy.toFloat())
                val bottom = PointF(cx + 0.5f, cy + 1f)
                val left = PointF(cx.toFloat(), cy + 0.5f)
                val right = PointF(cx + 1f, cy + 0.5f)
                when (code) {
                    1 -> segs.add(Seg(left, bottom))
                    2 -> segs.add(Seg(bottom, right))
                    3 -> segs.add(Seg(left, right))
                    4 -> segs.add(Seg(right, top))
                    5 -> { segs.add(Seg(top, left)); segs.add(Seg(bottom, right)) }
                    6 -> segs.add(Seg(bottom, top))
                    7 -> segs.add(Seg(left, top))
                    8 -> segs.add(Seg(top, left))
                    9 -> segs.add(Seg(top, bottom))
                    10 -> { segs.add(Seg(right, top)); segs.add(Seg(left, bottom)) }
                    11 -> segs.add(Seg(top, right))
                    12 -> segs.add(Seg(right, left))
                    13 -> segs.add(Seg(right, bottom))
                    14 -> segs.add(Seg(bottom, left))
                }
            }
        }

        fun key(p: PointF) = ((p.x * 4).roundToInt().toLong() shl 32) xor (p.y * 4).roundToInt().toLong()
        val byStart = HashMap<Long, MutableList<Int>>()
        segs.forEachIndexed { i, s -> byStart.getOrPut(key(s.a)) { ArrayList() }.add(i) }
        val used = BooleanArray(segs.size)
        val loops = ArrayList<List<PointF>>()
        for (startIdx in segs.indices) {
            if (used[startIdx]) continue
            val loop = ArrayList<PointF>()
            val startKey = key(segs[startIdx].a)
            var idx = startIdx
            var guard = 0
            while (guard++ <= segs.size + 2) {
                if (used[idx]) break
                used[idx] = true
                val seg = segs[idx]
                loop.add(seg.a)
                val nk = key(seg.b)
                if (nk == startKey) break
                val next = byStart[nk]?.firstOrNull { !used[it] } ?: break
                idx = next
            }
            if (loop.size >= 3) loops.add(loop)
        }

        val gx = srcW.toFloat() / mw
        val gy = srcH.toFloat() / mh
        val tolerance = max(srcW, srcH) * 0.006f
        val result = loops
            .map { loop -> simplifyLoop(loop.map { PointF(it.x * gx, it.y * gy) }, tolerance) }
            .filter { it.size >= 3 }
        if (useScale && sampled !== source) sampled.recycle()
        return result
    }

    private fun simplifyLoop(points: List<PointF>, tolerance: Float): List<PointF> {
        if (points.size <= 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        fun dp(lo: Int, hi: Int) {
            if (hi <= lo + 1) return
            var maxDist = -1f
            var maxIdx = -1
            val a = points[lo]; val b = points[hi]
            val dx = b.x - a.x; val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            for (i in lo + 1 until hi) {
                val p = points[i]
                val d = if (len2 < 1e-6f) {
                    hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble()).toFloat()
                } else {
                    val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
                    val projX = a.x + t * dx
                    val projY = a.y + t * dy
                    hypot((p.x - projX).toDouble(), (p.y - projY).toDouble()).toFloat()
                }
                if (d > maxDist) { maxDist = d; maxIdx = i }
            }
            if (maxDist > tolerance && maxIdx >= 0) {
                keep[maxIdx] = true
                dp(lo, maxIdx)
                dp(maxIdx, hi)
            }
        }
        dp(0, points.size - 1)
        return points.filterIndexed { i, _ -> keep[i] }
    }

    // ---------------------------------------------------------------
    // Álgebra 3D mínima (rotación XYZ, producto punto/cruz, normalizar)
    // — autocontenida a propósito, no hace falta traer una librería de
    // matemáticas para esto.
    // ---------------------------------------------------------------
    private class Rot3(rx: Double, ry: Double, rz: Double) {
        private val m: Array<DoubleArray>
        init {
            val cx = cos(rx); val sx = sin(rx)
            val cy = cos(ry); val sy = sin(ry)
            val cz = cos(rz); val sz = sin(rz)
            val rxM = arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0),
                doubleArrayOf(0.0, cx, -sx),
                doubleArrayOf(0.0, sx, cx)
            )
            val ryM = arrayOf(
                doubleArrayOf(cy, 0.0, sy),
                doubleArrayOf(0.0, 1.0, 0.0),
                doubleArrayOf(-sy, 0.0, cy)
            )
            val rzM = arrayOf(
                doubleArrayOf(cz, -sz, 0.0),
                doubleArrayOf(sz, cz, 0.0),
                doubleArrayOf(0.0, 0.0, 1.0)
            )
            m = matMul(matMul(rzM, ryM), rxM)
        }
        fun apply(p: DoubleArray): DoubleArray = doubleArrayOf(
            m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2],
            m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2],
            m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2]
        )
        companion object {
            fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
                val r = Array(3) { DoubleArray(3) }
                for (i in 0..2) for (j in 0..2) {
                    var s = 0.0
                    for (k in 0..2) s += a[i][k] * b[k][j]
                    r[i][j] = s
                }
                return r
            }
        }
    }

    private fun dot3(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross3(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun normalize3(a: DoubleArray): DoubleArray {
        val len = sqrt(dot3(a, a)).let { if (it < 1e-9) 1.0 else it }
        return doubleArrayOf(a[0] / len, a[1] / len, a[2] / len)
    }
}
