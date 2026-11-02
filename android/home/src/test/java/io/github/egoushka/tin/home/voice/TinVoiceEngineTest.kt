package io.github.egoushka.tin.home.voice

import android.os.IBinder
import android.os.RemoteException
import io.github.egoushka.tin.voice.IVoice
import io.github.egoushka.tin.voice.IVoiceCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 7: the engine against a fake of tin's VoiceService, no device and no Binder. */
class TinVoiceEngineTest {
    private class FakeVoice(var ready: Boolean = true) : IVoice {
        var callback: IVoiceCallback? = null
        var maxSeconds = 0.0
        val calls = mutableListOf<String>()
        var listenFails = false
        var readyFails = false

        override fun listen(maxSeconds: Double, cb: IVoiceCallback?) {
            calls += "listen"
            if (listenFails) throw RemoteException()
            this.maxSeconds = maxSeconds
            callback = cb
        }

        override fun cancel() {
            calls += "cancel"
        }

        override fun ready(): Boolean {
            if (readyFails) throw RemoteException()
            return ready
        }

        override fun asBinder(): IBinder? = null
    }

    private class FakeLink(var proxy: IVoice?) : VoiceLink {
        var binds = 0
        var unbinds = 0
        override var onLost: () -> Unit = {}
        override fun voice() = proxy
        override fun bind() { binds++ }
        override fun unbind() { unbinds++ }
    }

    private class CountingUses : VoiceUses {
        var count = 0
        override fun bump() { count++ }
    }

    private val voice = FakeVoice()
    private val link = FakeLink(voice)
    private val uses = CountingUses()
    private val engine = TinVoiceEngine(link, uses)
    private val shown = mutableListOf<VoiceState>()

    /** Runs the listen eagerly up to its first suspension, so the test can play tin's callbacks afterwards. */
    private fun CoroutineScope.listening() = async(Dispatchers.Unconfined) { engine.listen { shown += it } }

    @Test fun statesThenTextGiveListeningThinkingThenTheUtterance() = runBlocking {
        val heard = listening()
        voice.callback!!.onState(1)
        voice.callback!!.onState(2)
        voice.callback!!.onHeard("open calendar")

        assertEquals("open calendar", heard.await())
        assertEquals(listOf(VoiceState.Listening, VoiceState.Thinking), shown)
        assertEquals(TinVoiceEngine.MAX_SECONDS, voice.maxSeconds, 0.0)
        assertEquals(listOf("listen"), voice.calls)
    }

    @Test fun emptyTextIsNothingUnderstood() = runBlocking {
        val heard = listening()
        voice.callback!!.onHeard("  ")

        assertNull(heard.await())
        assertEquals(0, uses.count)
    }

    @Test fun anErrorFromTinEndsTheListenWithNothing() = runBlocking {
        val heard = listening()
        voice.callback!!.onError("mic busy")

        assertNull(heard.await())
        assertEquals(0, uses.count)
    }

    @Test fun anErrorShowsAsTheBarsErrorState() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val bar = Voice(engine)
        assertTrue(bar.start(scope) {})
        voice.callback!!.onError("mic busy")

        assertEquals(VoiceState.Error, bar.state.value)
        scope.cancel()
    }

    @Test fun anUnreadyTinMakesTheMicHandOver() {
        voice.ready = false
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

        assertFalse(Voice(engine).start(scope) {})
        assertTrue(voice.calls.isEmpty())
        scope.cancel()
    }

    @Test fun aMissingTinMakesTheMicHandOver() = runBlocking {
        link.proxy = null
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

        assertFalse(engine.ready())
        assertFalse(Voice(engine).start(scope) {})
        assertNull(engine.listen { shown += it })
        assertTrue(shown.isEmpty())
        scope.cancel()
    }

    @Test fun aDeadBinderMeansNotReadyAndNothingHeard() = runBlocking {
        voice.readyFails = true
        assertFalse(engine.ready())

        voice.listenFails = true
        assertNull(engine.listen { shown += it })
        assertEquals(0, uses.count)
    }

    @Test fun cancellingTheCoroutineCancelsTinAfterTheListen() = runBlocking {
        val heard = listening()
        heard.cancelAndJoin()

        assertEquals(listOf("listen", "cancel"), voice.calls)
    }

    @Test fun callbacksAfterACancelChangeNothing() = runBlocking {
        val heard = listening()
        val late = voice.callback!!
        heard.cancelAndJoin()
        late.onState(2)
        late.onHeard("approve the deploy")

        assertTrue(shown.isEmpty())
        assertEquals(0, uses.count)
    }

    @Test fun aSecondTapOnTheBarCancelsTheListen() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val bar = Voice(engine)
        var utterance: String? = null
        assertTrue(bar.start(scope) { utterance = it })
        assertEquals(VoiceState.Listening, bar.state.value)

        assertTrue(bar.start(scope) { utterance = it })

        assertEquals(listOf("listen", "cancel"), voice.calls)
        assertEquals(VoiceState.Idle, bar.state.value)
        assertNull(utterance)
        scope.cancel()
    }

    @Test fun theBarFollowsListeningThinkingIdleAndHandsOverTheText() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val bar = Voice(engine)
        var utterance: String? = null
        assertTrue(bar.start(scope) { utterance = it })
        assertEquals(VoiceState.Listening, bar.state.value)

        voice.callback!!.onState(2)
        assertEquals(VoiceState.Thinking, bar.state.value)

        voice.callback!!.onHeard("відкрий календар")
        assertEquals(VoiceState.Idle, bar.state.value)
        assertEquals("відкрий календар", utterance)
        scope.cancel()
    }

    @Test fun attachBindsAndDetachUnbinds() {
        engine.attach()
        assertEquals(1, link.binds)

        engine.detach()
        assertEquals(1, link.unbinds)
    }

    @Test fun leavingHomeCancelsAListenInFlight() = runBlocking {
        val heard = listening()
        engine.detach()
        heard.join()

        assertTrue(heard.isCancelled)
        assertEquals(listOf("listen", "cancel"), voice.calls)
        assertEquals(1, link.unbinds)
    }

    @Test fun tinDyingMidListenEndsItWithNothing() = runBlocking {
        val heard = listening()
        link.onLost()

        assertNull(heard.await())
        assertEquals(0, uses.count)
    }

    @Test fun theCounterGoesUpOncePerHeardText() = runBlocking {
        val first = listening()
        voice.callback!!.onHeard("open calendar")
        voice.callback!!.onHeard("open calendar")
        first.await()
        assertEquals(1, uses.count)

        val second = listening()
        voice.callback!!.onHeard("")
        second.await()
        assertEquals(1, uses.count)

        val third = listening()
        voice.callback!!.onHeard("відкрий календар")
        third.await()
        assertEquals(2, uses.count)
    }
}
