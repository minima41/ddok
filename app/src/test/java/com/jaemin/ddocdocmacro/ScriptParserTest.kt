package com.jaemin.ddocdocmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptParserTest {
    @Test fun parsesBookingScript() {
        val result = ScriptParser.parse("BOOK_APPOINTMENT 18000 30 1800 | 09:10,09:20,09:30")
        assertTrue(result.errors.isEmpty())
        val booking = result.steps.single() as MacroStep.BookAppointment
        assertEquals(listOf("09:10", "09:20", "09:30"), booking.priorities)
    }

    @Test fun rendersPatientAndPriorities() {
        val rendered = Prefs.renderTemplate(Prefs.CHAEEUM_PRESET_SCRIPT, "이도연", listOf("09:10", "09:30"))
        assertTrue(rendered.contains("TAP_PCT 50 48.3"))
        assertTrue(rendered.contains("BOOK_APPOINTMENT 18000 30 1800 | 09:10,09:30"))
        assertTrue(ScriptParser.parse(rendered).errors.isEmpty())
    }

    @Test fun rendersDoaCardPosition() {
        val rendered = Prefs.renderTemplate(Prefs.CHAEEUM_PRESET_SCRIPT, "이도아", listOf("09:10"))
        assertTrue(rendered.contains("TAP_PCT 50 38.0"))
        assertTrue(ScriptParser.parse(rendered).errors.isEmpty())
    }

    @Test fun rejectsBadTime() {
        assertTrue(ScriptParser.parse("BOOK_APPOINTMENT | 09:10,25:00").errors.isNotEmpty())
    }
}
