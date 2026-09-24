package com.hatsyrei.maidnative.domain.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RollDiceTest {

    private fun roll(arguments: String, seed: Int = 1) =
        JSONObject(RollDice.roll(JSONObject(arguments), Random(seed)))

    @Test
    fun `rolls stay on the die and total includes the modifier`() {
        val result = roll("""{"count":50,"sides":6,"modifier":3}""")
        val rolls = result.getJSONArray("rolls")
        val values = (0 until rolls.length()).map { rolls.getInt(it) }
        assertEquals(50, values.size)
        assertTrue(values.all { it in 1..6 })
        assertEquals(values.sum() + 3, result.getInt("total"))
        assertEquals("50d6+3", result.getString("notation"))
    }

    @Test
    fun `count defaults to one and negative modifiers read naturally`() {
        val result = roll("""{"sides":20,"modifier":-2}""")
        assertEquals(1, result.getJSONArray("rolls").length())
        assertEquals("1d20-2", result.getString("notation"))
    }

    @Test
    fun `numbers sent as strings or whole doubles are accepted`() {
        assertEquals("2d8", roll("""{"count":"2","sides":8.0}""").getString("notation"))
    }

    @Test
    fun `bad arguments come back as errors the model can read`() = runBlocking {
        for (args in listOf("{}", """{"sides":1}""", """{"sides":6,"count":0}""", """{"sides":6.5}""")) {
            val result = JSONObject(Tools.run(ToolCall("1", "roll_dice", args), listOf(RollDice)))
            assertTrue(args, result.has("error"))
        }
    }
}
