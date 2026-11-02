package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.signals.NeedsItem
import io.github.egoushka.tin.home.signals.NeedsYou
import io.github.egoushka.tin.home.voice.ask
import org.junit.Assert.assertEquals
import org.junit.Test

/** HS7: the "Ask Tin" row hands its line to the same path voice uses. */
class AskTest {
    private val waiting = NeedsYou.of(listOf(NeedsItem("1", "Deploy", "ci", 1L, true, "approvals://item/1")), 1, 1)

    /** What the line did: the call that ran, with its argument. */
    private fun run(said: String, needs: NeedsYou): List<String> {
        val calls = mutableListOf<String>()
        ask(said, needs, { calls += "item ${it}" }, { calls += "queue" }, { calls += "web ${it}" })
        return calls
    }

    @Test
    fun aDecidingWordOpensTheOldestWaitingItem() {
        assertEquals(listOf("item approvals://item/1"), run("approve the deploy", waiting))
    }

    @Test
    fun theUkrainianDecidingWordDoesToo() {
        assertEquals(listOf("item approvals://item/1"), run("підтверди деплой", waiting))
    }

    @Test
    fun withNothingWaitingItOpensTheQueue() {
        assertEquals(listOf("queue"), run("approve the deploy", NeedsYou.Empty))
    }

    @Test
    fun anyOtherLineIsASearch() {
        assertEquals(listOf("web weather in kyiv"), run("  weather in kyiv ", waiting))
    }

    @Test
    fun theDispatchOpensAndNeverDecides() {
        val calls = run("merge it", waiting)
        assertEquals(1, calls.size)
        assertEquals(true, calls.single().startsWith("item "))
    }
}
