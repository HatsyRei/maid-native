package com.hatsyrei.maidnative.domain.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class GetDatetimeTest {

    @Test
    fun `datetime carries local offset, weekday and zone`() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 24, 14, 5, 9)
        }
        val result = JSONObject(GetDatetime.format(calendar))
        assertEquals("2026-09-24T14:05:09+05:30", result.getString("datetime"))
        assertEquals("Thursday", result.getString("weekday"))
        assertEquals("Asia/Kolkata", result.getString("timezone"))
        assertFalse(GetDatetime.format(calendar).contains("\\/"))
    }

    @Test
    fun `negative offsets keep their sign`() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT-03:30")).apply {
            clear()
            set(2026, Calendar.JANUARY, 1, 0, 0, 0)
        }
        assertTrue(JSONObject(GetDatetime.format(calendar)).getString("datetime").endsWith("-03:30"))
    }

    @Test
    fun `get_datetime runs`() = runBlocking {
        val result = Tools.run(ToolCall("1", "get_datetime", ""), listOf(GetDatetime))
        assertTrue(JSONObject(result).has("datetime"))
    }
}
