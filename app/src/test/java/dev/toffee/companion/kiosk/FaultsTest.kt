package dev.toffee.companion.kiosk

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every subsystem that can break is on the screen that reports breakage.
 *
 * This exists because one was not. `WAKE` was added as a constant and
 * recorded against faithfully, but `all()` — which is what the maintenance
 * screen renders — still listed only three subsystems, so the one failure
 * with no other symptom ("he stopped answering to his name") was written to
 * a store that nothing displayed.
 *
 * Asserting the list by hand would have the same hole: whoever forgot to
 * add the subsystem would forget to add the assertion. So this asks the
 * class what subsystems it declares and insists the list covers all of
 * them. The next one cannot be forgotten.
 */
class FaultsTest {

    /** The declared subsystem constants, read off the class rather than
     *  copied from it. */
    private fun declared(): List<String> =
        Faults::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.isAccessible = true; it.get(Faults) as String }
            .filter { it.isNotBlank() }

    @Test
    fun everySubsystemItCanRecordIsASubsystemItCanReport() {
        val missing = declared().filterNot { it in Faults.SUBSYSTEMS }
        assertTrue(
            "these can be recorded but never reach the maintenance screen: " + missing,
            missing.isEmpty(),
        )
    }

    @Test
    fun theWakeWordIsOneOfThem() {
        // Named on its own because it is the one that was missing, and the
        // one whose failure is otherwise completely silent.
        assertTrue(Faults.WAKE in Faults.SUBSYSTEMS)
    }
}
