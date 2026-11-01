package com.narada.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.narada.android.connection.AndroidConnectionKeys
import com.narada.connection.Certificates
import com.narada.connection.ConnectionFailure
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform execution required. Never substituted by the software JVM fixture tests. */
@RunWith(AndroidJUnit4::class)
class NativeConnectionKeysTest {
    @Test fun generatedAndroidIdentityIsNonexportableStableAndCanSealAuthenticatedState() {
        val id=UUID.randomUUID().toString();val keys=AndroidConnectionKeys()
        try {
            keys.create(id);val first=keys.identity(id);assertNull(first.key.encoded);Certificates.verifyKey(first)
            assertEquals(first.fingerprint,keys.identity(id).fingerprint)
            val message="native-device-test-only".toByteArray();val sealed=keys.seal(id,message)
            assertArrayEquals(message,keys.unseal(id,sealed))
            sealed[sealed.lastIndex]=(sealed.last().toInt() xor 1).toByte()
            assertThrows(Exception::class.java){keys.unseal(id,sealed)}
        } finally {
            // Only this test's two random aliases, never the application's enrolled identity.
            KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.let { it.deleteEntry("narada.connection.ec.$id");it.deleteEntry("narada.connection.seal.$id") }
        }
    }
    @Test fun absentNativeIdentityHasNoSoftwareFallback() {
        val id=UUID.randomUUID().toString()
        assertThrows(ConnectionFailure::class.java){AndroidConnectionKeys().identity(id)}
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        assertFalse(store.containsAlias("narada.connection.ec.$id"));assertFalse(store.containsAlias("narada.connection.seal.$id"))
    }
}
