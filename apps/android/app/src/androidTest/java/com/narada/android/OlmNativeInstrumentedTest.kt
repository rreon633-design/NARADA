package com.narada.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.narada.connection.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compiling/packaging this is not execution. Run on the device/ABI/API/16-KiB matrix before qualification. */
@RunWith(AndroidJUnit4::class)
class OlmNativeInstrumentedTest {
    @Test fun bundledLibraryReportsTheExactDesktopMode(){assertEquals(OlmInfo(2,"0.11.1","olm.v1",64,false),VodozemacOlm().info())}
    @Test fun nativeAccountPicklesAndSessionsRoundTripOnThisAbi(){val c=VodozemacOlm();val a=c.bundle(c.create().account,"a".repeat(64),"b".repeat(64));val b=c.bundle(c.create().account,"b".repeat(64),"a".repeat(64));val body=ChatBody(message_id=UUID.randomUUID().toString(),sender="a".repeat(64),recipient="b".repeat(64),text="Native device fixture — నమస్కారం");val encrypted=c.encrypt(a.account,a.bundle,b.bundle,null,body);c.inspectSession(encrypted.session);val received=c.decrypt(b.account,b.bundle,a.bundle,emptyList(),encrypted.envelope);assertEquals(body.text,received.text);val reply=body.copy(message_id=UUID.randomUUID().toString(),sender=body.recipient,recipient=body.sender,text="reply");val encoded=c.encrypt(received.account,b.bundle,a.bundle,received.session,reply);assertEquals(reply.text,c.decrypt(a.account,a.bundle,b.bundle,listOf(encrypted.session),encoded.envelope).text)}
}
