package io.github.hassanjaveed84.aatube.core

// Adapted from AA Suite (https://github.com/iAlias/AASuite), MIT License.
// See docs/AASUITE_LICENSE.txt.

data class FitRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Letterbox maths: the largest source-shaped rectangle centered in the destination. */
object AspectFit {
    fun fit(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): FitRect {
        require(srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0) {
            "dimensions must be positive: src=${srcWidth}x$srcHeight dst=${dstWidth}x$dstHeight"
        }
        val scale = minOf(dstWidth.toFloat() / srcWidth, dstHeight.toFloat() / srcHeight)
        val width = (srcWidth * scale).toInt()
        val height = (srcHeight * scale).toInt()
        return FitRect((dstWidth - width) / 2, (dstHeight - height) / 2, width, height)
    }
}

/**
 * How far a WebView must zoom out to give a page the viewport it expects.
 * The host paints the virtual display pixel for pixel, so a bigger virtual
 * display only crops; zooming the WebView out is what widens the viewport.
 */
object WebViewport {
    fun initialScalePercent(surfaceWidth: Int, targetWidth: Int): Int {
        if (surfaceWidth <= 0 || targetWidth <= 0 || surfaceWidth >= targetWidth) return 100
        return surfaceWidth * 100 / targetWidth
    }
}
