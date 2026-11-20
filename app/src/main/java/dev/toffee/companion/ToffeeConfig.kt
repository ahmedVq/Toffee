package dev.toffee.companion

/**
 * Which Supabase project this tablet belongs to, and how to reach it.
 *
 * Both values come from the repo-root `.env` at build time, through
 * `app/build.gradle.kts`, which names them one by one rather than looping
 * over the file. That matters: `.env` also holds the service-role key, a
 * Supabase personal access token and the Gemini key, and none of those may
 * ever sit on a tablet that lives on a desk.
 *
 * The device's own credential is not here either. It is typed in once at
 * setup and stored encrypted — see `net/TokenStore.kt`.
 */
object ToffeeConfig {

    /** No trailing slash, whatever `.env` happens to hold. */
    val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')

    /** The publishable key. Safe to ship: on its own it reaches nothing,
     *  because every table has RLS and every row is gated on the device's
     *  own `app_metadata.device` claim. */
    val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    /** An Edge Function, e.g. `functionUrl("turn")`. */
    fun functionUrl(slug: String): String = join(baseUrl, "functions/v1/$slug")

    /** A PostgREST table or view, e.g. `restUrl("reminders")`. */
    fun restUrl(path: String): String = join(baseUrl, "rest/v1/$path")

    /** The Supabase Auth endpoint, e.g. `authUrl("token")`. */
    fun authUrl(path: String): String = join(baseUrl, "auth/v1/$path")

    internal fun join(base: String, path: String): String =
        "${base.trimEnd('/')}/${path.trimStart('/')}"
}
