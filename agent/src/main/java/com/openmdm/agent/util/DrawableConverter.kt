package com.openmdm.agent.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for converting Android Drawables to Compose ImageBitmaps.
 *
 * Handles conversion of various Drawable types to ImageBitmap for use in Compose UI.
 */
@Singleton
class DrawableConverter @Inject constructor() {

    /**
     * Default size for drawables that don't have intrinsic dimensions.
     */
    private val defaultSize = 96

    /**
     * Convert a Drawable to an ImageBitmap.
     *
     * Handles both BitmapDrawable (direct conversion) and other Drawable types
     * (renders to a new Bitmap).
     *
     * @param drawable The drawable to convert, or null
     * @return The converted ImageBitmap, or null if conversion fails
     */
    fun toImageBitmap(drawable: Drawable?): ImageBitmap? {
        if (drawable == null) return null

        return try {
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: defaultSize
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: defaultSize
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert a Drawable to a Bitmap.
     *
     * @param drawable The drawable to convert, or null
     * @return The converted Bitmap, or null if conversion fails
     */
    fun toBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null

        return try {
            if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: defaultSize
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: defaultSize
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }
}
