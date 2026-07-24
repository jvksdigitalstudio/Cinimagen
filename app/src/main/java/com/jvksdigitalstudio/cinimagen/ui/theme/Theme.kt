package com.jvksdigitalstudio.cinimagen.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Paleta de marca única de Cinimagen: degradado morado → azul, con el
 * morado como color dominante. Este archivo es la ÚNICA fuente de verdad
 * para el color de la app — cualquier pantalla nueva debe usar
 * [CinimagenGradient] como fondo y [CinimagenTheme] como wrapper de
 * MaterialTheme, para que la identidad visual sea 100% consistente en
 * toda la app (Mis proyectos, Editor, diálogos, etc).
 */

// --- Morado (dominante) ---
val BrandPurpleDeep = Color(0xFF1E0F45)   // esquina superior, casi negro-morado
val BrandPurple = Color(0xFF4B2A9E)       // morado principal de marca
val BrandPurpleLight = Color(0xFF7C4DFF)  // morado vivo, para acentos/botones

// --- Azul (minoría, cierre del degradado) ---
val BrandBlue = Color(0xFF2F5FE0)         // azul principal de marca
val BrandBlueLight = Color(0xFF5B8DEF)    // azul vivo, para acentos secundarios
val BrandBlueDeep = Color(0xFF10214F)     // cierre inferior, azul oscuro

// --- Superficies neutras con tinte morado (para paneles, tarjetas, etc.) ---
val SurfaceTintedDark = Color(0xFF16102C)
val SurfaceTintedElevated = Color(0xFF241A46)

/**
 * Verde chroma-key (croma), el mismo tono que usan los telones físicos de
 * cine/TV (RGB 0,177,64 — separa bien de tonos de piel y evita "spill").
 * Es el fondo POR DEFECTO del lienzo de preview/edición cuando no hay
 * nada cubriendo esa zona: así cualquier capa que el usuario recorte o
 * exporte queda lista para hacer chroma key en otro software si quiere,
 * o puede reemplazarlo con su propio fondo (botón "F" de importar fondo).
 */
val ChromaKeyGreen = Color(0xFF00B140)

/**
 * El degradado oficial de toda la app: morado arriba, azul abajo, con el
 * morado ocupando la mayor parte del recorrido (~65-70%) antes de
 * transicionar a azul cerca del final, tal como pide la identidad de
 * marca ("que predomine el morado").
 */
val CinimagenGradient = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to BrandPurpleDeep,
        0.55f to BrandPurple,
        0.88f to BrandPurpleLight.copy(alpha = 0.9f).compositeOverPurple(),
        1.0f to BrandBlue
    )
)

// Pequeño helper para que el stop de transición se sienta como una mezcla
// morado→azul y no un salto brusco de morado vivo a azul.
private fun Color.compositeOverPurple(): Color = Color(
    red = (this.red * 0.7f + BrandBlue.red * 0.3f),
    green = (this.green * 0.7f + BrandBlue.green * 0.3f),
    blue = (this.blue * 0.7f + BrandBlue.blue * 0.3f),
    alpha = 1f
)

/** Variante más sutil del degradado, para paneles internos (no pantalla completa). */
val CinimagenGradientSubtle = Brush.verticalGradient(
    colors = listOf(SurfaceTintedDark, SurfaceTintedElevated, SurfaceTintedDark)
)

private val CinimagenColorScheme = darkColorScheme(
    primary = BrandPurpleLight,
    onPrimary = Color.White,
    primaryContainer = BrandPurple,
    onPrimaryContainer = Color.White,
    secondary = BrandBlueLight,
    onSecondary = Color.White,
    secondaryContainer = BrandBlue,
    onSecondaryContainer = Color.White,
    tertiary = BrandBlueLight,
    onTertiary = Color.White,
    background = BrandPurpleDeep,
    onBackground = Color.White,
    surface = SurfaceTintedDark,
    onSurface = Color.White,
    surfaceVariant = SurfaceTintedElevated,
    onSurfaceVariant = Color(0xFFD6CFEF),
    outline = Color(0xFF8A7DB8)
)

@Composable
fun CinimagenTheme(content: @Composable () -> Unit) {
    // La app tiene una identidad de marca fija (degradado morado→azul) que
    // no depende del tema claro/oscuro del sistema — siempre se usa el
    // esquema oscuro de marca para mantener congruencia en todas las
    // pantallas y opciones.
    MaterialTheme(
        colorScheme = CinimagenColorScheme,
        content = content
    )
}
