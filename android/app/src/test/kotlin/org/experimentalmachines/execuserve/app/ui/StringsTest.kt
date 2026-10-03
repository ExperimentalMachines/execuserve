package org.experimentalmachines.execuserve.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tablets (smallest width 600dp and up) read values-sw600dp/strings.xml over values/. Nothing
 * else ties the two together, so this does: every string that says "phone" has a tablet copy,
 * no tablet copy says "phone", and every tablet copy overrides a string that exists.
 */
class StringsTest {
    private fun strings(folder: String): Map<String, String> {
        val xml = File("src/main/res/$folder/strings.xml").readText()
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val phone = strings("values")
    private val tablet = strings("values-sw600dp")

    /** Strings that say "phone" about something other than this device. */
    private val notAboutThisDevice = setOf("catalog_over_budget")

    @Test
    fun everyPhoneStringHasATabletCopy() {
        val missing = phone.filter { (name, text) -> "phone" in text.lowercase() && name !in notAboutThisDevice && name !in tablet }.keys
        assertTrue("Add these to values-sw600dp/strings.xml: $missing", missing.isEmpty())
    }

    @Test
    fun noTabletCopySaysPhone() {
        val wrong = tablet.filterValues { "phone" in it.lowercase() }.keys
        assertTrue("These tablet strings still say phone: $wrong", wrong.isEmpty())
    }

    @Test
    fun tabletCopiesOverrideRealStringsWithTheSamePlaceholders() {
        assertEquals("Tablet strings with no phone original", emptySet<String>(), tablet.keys - phone.keys)
        val placeholder = Regex("""%(\d+\$)?[sd]""")
        tablet.forEach { (name, text) ->
            assertEquals(name, placeholder.findAll(phone.getValue(name)).map { it.value }.toList(), placeholder.findAll(text).map { it.value }.toList())
        }
    }
}
