package com.hatsyrei.maidnative.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallIndexTest {

    private fun indices(vararg fragments: String): List<Int> {
        val index = OpenAiClient.ToolCallIndex()
        return fragments.map { index.of(JSONObject(it)) }
    }

    @Test
    fun `explicit indices are kept`() {
        assertEquals(
            listOf(0, 0, 1, 1),
            indices(
                """{"index":0,"id":"a","function":{"name":"f","arguments":""}}""",
                """{"index":0,"function":{"arguments":"{}"}}""",
                """{"index":1,"id":"b","function":{"name":"f","arguments":""}}""",
                """{"index":1,"function":{"arguments":"{}"}}""",
            ),
        )
    }

    @Test
    fun `unindexed calls with new ids are separate calls`() {
        assertEquals(
            listOf(0, 1),
            indices(
                """{"id":"call_242494","function":{"name":"roll_dice","arguments":"{}"}}""",
                """{"id":"call_242496","function":{"name":"roll_dice","arguments":"{}"}}""",
            ),
        )
    }

    @Test
    fun `unindexed fragments without an id continue the call`() {
        assertEquals(
            listOf(0, 0, 1),
            indices(
                """{"id":"a","function":{"name":"f","arguments":"{\"x\""}}""",
                """{"function":{"arguments":":1}"}}""",
                """{"id":"b","function":{"name":"f","arguments":"{}"}}""",
            ),
        )
    }
}
