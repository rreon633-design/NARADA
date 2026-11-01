package com.narada.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a real Android emulator/device. Compiling this class is NOT execution evidence. */
@RunWith(AndroidJUnit4::class)
class NativeFoundationSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualActivityLaunchesAndRecreatesWithExplicitNetworkBoundary() {
        compose.onRoot().assertExists()
        compose.activityRule.scenario.recreate()
        compose.onRoot().assertExists()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val requested=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(requested.contains(Manifest.permission.INTERNET))
        assertFalse(requested.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertFalse(requested.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
    }
}
