package io.synctuary.android.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeFormatParseTest {

    // ---- formatTimeHms ----

    @Test
    fun hms_zero() = assertEquals("00:00:00", formatTimeHms(0L))

    @Test
    fun hms_underAMinute() = assertEquals("00:00:07", formatTimeHms(7_000L))

    @Test
    fun hms_minutes() = assertEquals("00:03:27", formatTimeHms(207_000L))

    @Test
    fun hms_hours() = assertEquals("01:02:03", formatTimeHms(3_723_000L))

    @Test
    fun hms_negativeClampsToZero() = assertEquals("00:00:00", formatTimeHms(-5_000L))

    // ---- parseTimeInput ----

    @Test
    fun parse_plainSeconds() = assertEquals(90_000L, parseTimeInput("90"))

    @Test
    fun parse_mmss() = assertEquals(207_000L, parseTimeInput("3:27"))

    @Test
    fun parse_hhmmss() = assertEquals(3_723_000L, parseTimeInput("1:02:03"))

    @Test
    fun parse_zeroPadded() = assertEquals(3_723_000L, parseTimeInput("01:02:03"))

    @Test
    fun parse_trimsWhitespace() = assertEquals(60_000L, parseTimeInput(" 1:00 "))

    @Test
    fun parse_rejectsEmpty() = assertNull(parseTimeInput(""))

    @Test
    fun parse_rejectsNonNumeric() = assertNull(parseTimeInput("ab:cd"))

    @Test
    fun parse_rejectsTooManyFields() = assertNull(parseTimeInput("1:2:3:4"))

    @Test
    fun parse_rejectsSecondsOver59WithMinutes() = assertNull(parseTimeInput("1:75"))

    @Test
    fun parse_rejectsMinutesOver59WithHours() = assertNull(parseTimeInput("1:75:00"))

    @Test
    fun parse_rejectsBlankField() = assertNull(parseTimeInput("1::30"))

    @Test
    fun parse_rejectsNegative() = assertNull(parseTimeInput("-5"))
}
