package com.narada.android

import com.narada.android.transfers.BoundedProviderIo
import com.narada.connection.ConnectionFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BoundedProviderIoTest {
    private fun ignoreInterrupts(latch:CountDownLatch){while(latch.count>0)try{latch.await()}catch(_:InterruptedException){}}
    private suspend fun idle(io:BoundedProviderIo)=withTimeout(3000){while(io.busy)delay(2)}
    private suspend fun failure(code:String,body:suspend ()->Unit){try{body();org.junit.Assert.fail("Expected failure")}catch(e:ConnectionFailure){assertEquals(code,e.code)}}
    @Test fun successReleasesOnlyAfterTheSingleWorkerFinishes()=runBlocking<Unit>{val io=BoundedProviderIo(2000);val calls=AtomicInteger();io.onIdle={calls.incrementAndGet()};io.run({org.junit.Assert.fail("No cancellation expected")}){guard->guard();assertTrue(io.busy);assertTrue(Thread.currentThread().name.startsWith("narada-provider-copy"))};idle(io);assertEquals(1,calls.get())}
    @Test fun timedOutUncooperativeWorkerIsQuarantinedAndLateSuccessFenced()=runBlocking<Unit>{val io=BoundedProviderIo(60);val release=CountDownLatch(1);val late=AtomicBoolean();val cancel=AtomicInteger();try{failure("PROVIDER_TIMEOUT"){io.run({cancel.incrementAndGet()}){guard->ignoreInterrupts(release);guard();late.set(true)}};assertTrue(io.busy);failure("PROVIDER_BUSY"){io.run({}){org.junit.Assert.fail("No replacement worker")}};assertFalse(late.get());release.countDown();idle(io);assertFalse(late.get());assertEquals(1,cancel.get())}finally{release.countDown()}}
    @Test fun blockedCancellationHelperAlsoRetainsTheOneSlot()=runBlocking<Unit>{val io=BoundedProviderIo(60);val cancelEntered=CountDownLatch(1);val cancelRelease=CountDownLatch(1);try{failure("PROVIDER_TIMEOUT"){io.run({cancelEntered.countDown();ignoreInterrupts(cancelRelease)}){delay(30000)}};assertTrue(cancelEntered.await(2,TimeUnit.SECONDS));assertTrue(io.busy);failure("PROVIDER_BUSY"){io.run({}){}};cancelRelease.countDown();idle(io)}finally{cancelRelease.countDown()}}
    @Test fun coroutineCancellationBoundsCallerWaitNotHostileProviderLifetime()=runBlocking<Unit>{val io=BoundedProviderIo(5000);val entered=CountDownLatch(1);val release=CountDownLatch(1);val late=AtomicBoolean();try{val job=launch(Dispatchers.Default){io.run({}){guard->entered.countDown();ignoreInterrupts(release);guard();late.set(true)}};assertTrue(entered.await(2,TimeUnit.SECONDS));withTimeout(2000){job.cancelAndJoin()};assertTrue(io.busy);release.countDown();idle(io);assertFalse(late.get())}finally{release.countDown()}}
    @Test fun providerFailureUsesStableCodeAndDoesNotLeakASlot()=runBlocking<Unit>{val io=BoundedProviderIo(2000);failure("PROVIDER_PERMISSION_LOST"){io.run({}){throw ConnectionFailure("PROVIDER_PERMISSION_LOST")}};idle(io);io.run({}){it()};idle(io)}
    @Test fun monotonicDeadlineGuardDoesNotDependOnCallerScheduling()=runBlocking<Unit>{val clock=java.util.concurrent.atomic.AtomicLong(0);val io=BoundedProviderIo(5000){clock.get()};failure("EXPORT_INTERRUPTED"){io.run({}){guard->clock.set(TimeUnit.MILLISECONDS.toNanos(5001));guard()}};idle(io)}

}
