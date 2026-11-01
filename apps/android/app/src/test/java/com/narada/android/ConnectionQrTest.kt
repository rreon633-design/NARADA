package com.narada.android

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.narada.android.connection.PairingCapture
import com.narada.android.connection.QrDecoder
import com.narada.connection.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class ConnectionQrTest {
    @Test fun actualOfflineQrDecoderRecoversOnlyValidInvitationBytes() {
        val code=AndroidTestIdentities.code(AndroidTestIdentities.create(true));val text=Wire.json.encodeToString(PairCode.serializer(),code)
        val matrix=MultiFormatWriter().encode(text,BarcodeFormat.QR_CODE,1000,1000)
        val bitmap=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(1_000_000){i->if(matrix[i%1000,i/1000])android.graphics.Color.BLACK else android.graphics.Color.WHITE}
        bitmap.setPixels(pixels,0,1000,0,0,1000,1000)
        assertEquals(text,QrDecoder.decode(bitmap));bitmap.recycle()
    }
    @Test fun arbitraryQrUrlsCannotBecomePairingInvitations() {
        val matrix=MultiFormatWriter().encode("https://example.com",BarcodeFormat.QR_CODE,300,300)
        val bitmap=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(90_000){i->if(matrix[i%300,i/300])android.graphics.Color.BLACK else android.graphics.Color.WHITE},0,300,0,0,300,300)
        try { QrDecoder.decode(bitmap);org.junit.Assert.fail("URL accepted as pairing") }catch(_:ConnectionFailure){}finally{bitmap.recycle()}
    }
    @Test fun unconsumedCaptureExpiresWithoutSavingAResultIntent() {
        val capture=PairingCapture();val text=Wire.json.encodeToString(PairCode.serializer(),AndroidTestIdentities.code(AndroidTestIdentities.create(true)))
        capture.put(text);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(151))
        assertNull(capture.take())
    }
    @Test fun cameraCaptureIsSingleUseMemoryOnly() {
        val capture=PairingCapture();val code=AndroidTestIdentities.code(AndroidTestIdentities.create(true));val text=Wire.json.encodeToString(PairCode.serializer(),code)
        capture.put(text);assertEquals(text,capture.take());assertNull(capture.take());capture.put(text);capture.clear();assertNull(capture.take())
    }
}
