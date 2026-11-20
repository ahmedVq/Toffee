package dev.toffee.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToffeeConfigTest {

    @Test
    fun `functionUrl builds the edge function path`() {
        assertEquals(
            "https://gzwgczqktudzorotryva.supabase.co/functions/v1/turn",
            ToffeeConfig.functionUrl("turn"),
        )
    }

    @Test
    fun `restUrl builds the postgrest path`() {
        assertEquals(
            "https://gzwgczqktudzorotryva.supabase.co/rest/v1/reminders",
            ToffeeConfig.restUrl("reminders"),
        )
    }

    @Test
    fun `a trailing slash in the base url does not double up`() {
        assertEquals(
            "https://x.supabase.co/rest/v1/reminders",
            ToffeeConfig.join("https://x.supabase.co/", "rest/v1/reminders"),
        )
    }

    // The anti-goal is "no secrets on the device". The tablet sits on a desk
    // in a room, and the whole security story is that losing it costs
    // nothing: disable one auth user and it goes inert. That holds only if
    // the APK carries no key that outlives it. This test is the gate.
    @Test
    fun `the compiled key is the publishable one`() {
        assertTrue(
            "the publishable key must be the sb_publishable_ one",
            ToffeeConfig.publishableKey.startsWith("sb_publishable_"),
        )
        assertFalse(
            "a secret key must never be compiled in",
            ToffeeConfig.publishableKey.startsWith("sb_secret_"),
        )
    }

    @Test
    fun `no secret-shaped field exists in BuildConfig at all`() {
        val fields = BuildConfig::class.java.declaredFields.map { it.name.uppercase() }
        for (banned in listOf("SERVICE_ROLE", "SECRET", "GEMINI", "ACCESS_TOKEN")) {
            assertFalse(
                "BuildConfig must not carry a field named $banned, found in $fields",
                fields.any { it.contains(banned) },
            )
        }
    }

    @Test
    fun `no BuildConfig value looks like a secret`() {
        // Belt and braces: a secret smuggled in under an innocent field name
        // would pass the test above. This one reads the values.
        val values = BuildConfig::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(null) as String? }
        for (v in values) {
            assertFalse("a secret key reached BuildConfig", v.startsWith("sb_secret_"))
            assertFalse("a supabase PAT reached BuildConfig", v.startsWith("sbp_"))
            assertFalse("a gemini key reached BuildConfig", v.startsWith("AQ."))
        }
    }
}
