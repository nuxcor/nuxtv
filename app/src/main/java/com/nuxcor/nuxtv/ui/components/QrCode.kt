package com.nuxcor.nuxtv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * A QR code rendered for a TV panel: generated at module resolution and scaled
 * with [FilterQuality.None] so the squares stay razor-sharp instead of
 * bilinear-blurring — a fuzzy QR at 3 metres is a QR the phone won't lock onto.
 */
@Composable
fun QrCode(data: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(data) {
        val matrix = QRCodeWriter().encode(
            data,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0),
        )
        val size = matrix.width
        val pixels = IntArray(size * size) { i ->
            val filled = matrix.get(i % size, i / size)
            if (filled) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.None,
        modifier = modifier,
    )
}
