package com.narada.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.work.WorkManager
import com.narada.android.work.AndroidWorkScheduler
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Hilt application/activity/repositories under host Android shadows; not a device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=NaradaApplication::class,qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApplicationIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualApplicationLaunchesAndRecreatesWithoutCreatingWork() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Get started").assertIsDisplayed()
        val manager=WorkManager.getInstance(compose.activity.applicationContext)
        assertTrue(manager.getWorkInfosByTag(AndroidWorkScheduler.TAG).get(5,TimeUnit.SECONDS).isEmpty())
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Get started").assertIsDisplayed()
        assertTrue(manager.getWorkInfosByTag(AndroidWorkScheduler.TAG).get(5,TimeUnit.SECONDS).isEmpty())
    }
}
