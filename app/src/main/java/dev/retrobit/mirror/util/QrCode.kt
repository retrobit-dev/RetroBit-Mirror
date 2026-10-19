package dev.retrobit.mirror.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrCode {
    fun bitmap(text: String, size: Int = 512): Bitmap? {
        return try {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.parseColor("#07080C") else Color.parseColor("#F4F1EA"))
                }
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}
