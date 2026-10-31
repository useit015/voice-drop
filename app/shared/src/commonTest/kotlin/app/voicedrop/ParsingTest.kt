package app.voicedrop

import app.voicedrop.data.phoneOf
import app.voicedrop.data.preview
import app.voicedrop.sounds.MyInstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParsingTest {
    private val listing = """
        <div class="instant">
          <div class="circle small-button-background" style="background-color:#FF0000"></div>
          <button class="small-button" onclick="play('/media/sounds/bruh.mp3', 'loader-1', 'bruh-1')"></button>
          <a href="/en/instant/bruh/" class="instant-link link-secondary">Bruh &amp; co</a>
        </div>
        <div class="instant">
          <button class="small-button" onclick="play('https://evil.example/x.mp3', 'l', 'x')"></button>
          <a href="/en/instant/x/" class="instant-link">Evil</a>
        </div>
    """.trimIndent()

    @Test
    fun parsesMyInstantsListing() {
        val sounds = MyInstants.parseListing(listing)
        assertEquals(1, sounds.size)
        assertEquals("Bruh & co", sounds[0].title)
        assertEquals("https://www.myinstants.com/media/sounds/bruh.mp3", sounds[0].mp3)
        assertEquals("#FF0000", sounds[0].color)
    }

    @Test
    fun onlyMyInstantsAudioIsAllowed() {
        assertTrue(MyInstants.isSoundUrl("https://www.myinstants.com/media/sounds/a.mp3"))
        assertFalse(MyInstants.isSoundUrl("https://www.myinstants.com/media/sounds/a.mp3?x=1"))
        assertFalse(MyInstants.isSoundUrl("http://www.myinstants.com/media/sounds/a.mp3"))
        assertFalse(MyInstants.isSoundUrl("https://myinstants.com.evil.io/media/sounds/a.mp3"))
    }

    @Test
    fun previews() {
        assertEquals("🎤 Voice message (0:07)", preview("voice", null, 7))
        assertEquals("📷 Photo", preview("image", ""))
        assertEquals("📷 nice", preview("image", "nice"))
        assertEquals("+212612345678", phoneOf("212612345678@s.whatsapp.net"))
    }
}
