package com.hatsyrei.maidnative.domain.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class RunJavaScriptTest {

    @Test
    fun `missing code comes back as an error`() = runBlocking {
        val result = JSONObject(Tools.run(ToolCall("1", "run_javascript", "{}"), listOf(RunJavaScript)))
        assertTrue(result.getString("error").contains("code"))
    }

    @Test
    fun `no sandbox comes back as an error`() = runBlocking {
        val result = JSONObject(Tools.run(ToolCall("1", "run_javascript", """{"code":"1+1"}"""), listOf(RunJavaScript)))
        assertTrue(result.getString("error").contains("unavailable"))
    }

    @Test
    fun `code is embedded as a string literal`() {
        val script = RunJavaScript.wrap("'\"); evil(); (\"\n", promises = true)
        assertTrue(script.contains("""(0, eval)("'\"); evil(); (\"\n")"""))
    }
}
