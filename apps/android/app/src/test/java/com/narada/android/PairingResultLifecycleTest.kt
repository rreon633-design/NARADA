package com.narada.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PairingResultLifecycleTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Test fun waitsForStartedBeforeConsumingAnInvitation() = runTest {
        val owner = Owner(); owner.registry.currentState = Lifecycle.State.CREATED
        var calls = 0; launch { withStartedPairingResult(owner.lifecycle) { calls++ } }
        runCurrent(); assertEquals(0, calls)
        owner.registry.currentState = Lifecycle.State.STARTED
        runCurrent(); assertEquals(1, calls)
    }
    @Test fun pendingResultYieldsBeforeReadingTheForegroundFence() = runTest {
        val owner = Owner(); owner.registry.currentState = Lifecycle.State.STARTED
        var foreground = false; var observed = false
        val pending = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withStartedPairingResult(owner.lifecycle) { observed = foreground } }
        assertFalse(observed); foreground = true; runCurrent(); pending.join(); assertTrue(observed)
    }
    @Test fun stopOrDestroyBeforeDeferredConsumptionDropsTheResult() = runTest {
        val owner = Owner(); owner.registry.currentState = Lifecycle.State.STARTED
        var calls = 0
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withStartedPairingResult(owner.lifecycle) { calls++ } }
        owner.registry.currentState = Lifecycle.State.CREATED; runCurrent(); assertEquals(0, calls)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        withStartedPairingResult(owner.lifecycle) { calls++ }; assertEquals(0, calls)
    }
    @Test fun inputWaitsForPreparationAndSuppressesDuplicateLaunchesUntilReturn()=runTest {
        val gate=PairingInputGate();val ready=kotlinx.coroutines.CompletableDeferred<Boolean>()
        var prepared=0;var opened=0
        val first=launch { gate.start({true},{prepared++;ready.await()},{opened++}) }
        runCurrent();assertEquals(1,prepared);assertEquals(0,opened)
        assertFalse(gate.start({true},{prepared++;true},{opened++}))
        ready.complete(true);runCurrent();first.join();assertEquals(1,opened)
        assertFalse(gate.start({true},{true},{opened++}))
        gate.returned();assertTrue(gate.start({true},{true},{opened++}));assertEquals(2,opened)
    }
    @Test fun failedOrBackgroundedPreparationDoesNotLaunchAndCanBeRetried()=runTest {
        val gate=PairingInputGate();var started=true;var opened=0
        assertFalse(gate.start({started},{false},{opened++}))
        assertFalse(gate.start({started},{started=false;true},{opened++}));assertEquals(0,opened)
        started=true;assertTrue(gate.start({started},{true},{opened++}));assertEquals(1,opened)
    }
    @Test fun cancelledPreparationReleasesTheInputGateWithoutALateLaunch()=runTest {
        val gate=PairingInputGate();val ready=kotlinx.coroutines.CompletableDeferred<Boolean>();var opened=0
        val job=launch { gate.start({true},{ready.await()},{opened++}) }
        runCurrent();job.cancel();runCurrent();ready.complete(true);runCurrent();assertEquals(0,opened)
        assertTrue(gate.start({true},{true},{opened++}));assertEquals(1,opened)
    }
    @Test fun inputResultConsumptionIsAwaitedBeforeReleasingItsCaller()=runTest {
        val owner=Owner();owner.registry.currentState=Lifecycle.State.STARTED
        val done=kotlinx.coroutines.CompletableDeferred<Unit>();var returned=false
        launch { withStartedPairingResult(owner.lifecycle){done.await()};returned=true }
        runCurrent();assertFalse(returned);done.complete(Unit);runCurrent();assertTrue(returned)
    }

    @Test fun leavingAndReturningToTheDevicesScreenInvalidatesQueuedInput()=runTest {
        val gate=PairingInputGate();var opened=0
        assertFalse(gate.start({true},{gate.invalidate();true},{opened++}))
        assertEquals(0,opened);assertTrue(gate.start({true},{true},{opened++}));assertEquals(1,opened)
    }

}
