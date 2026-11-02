package io.github.egoushka.tin.home.voice

import io.github.egoushka.tin.home.signals.NeedsItem
import io.github.egoushka.tin.home.signals.NeedsYou
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIntentTest {
    private val waiting = NeedsYou.of(
        listOf(
            NeedsItem("2", "Merge PR", "github", 200, false, "approvals://item/2"),
            NeedsItem("1", "Deploy", "homelab", 100, false, "approvals://item/1"),
        ),
        open = 2, held = 0,
    )

    @Test fun decidingWordOpensTheOldestItem() {
        assertEquals(VoiceIntent.OpenItem("approvals://item/1"), VoiceIntent.of("Approve the deploy", waiting))
    }

    @Test fun ukrainianDecidingWordOpensTheOldestItem() {
        assertEquals(VoiceIntent.OpenItem("approvals://item/1"), VoiceIntent.of("підтверди деплой", waiting))
    }

    @Test fun decidingWordWithNothingOpenOpensTheQueue() {
        assertEquals(VoiceIntent.OpenQueue, VoiceIntent.of("merge it", NeedsYou.Empty))
    }

    @Test fun everythingElseIsASearch() {
        assertEquals(VoiceIntent.Search("weather in Kyiv"), VoiceIntent.of(" weather in Kyiv ", waiting))
    }
}
