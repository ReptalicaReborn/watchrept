package com.dihcord.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun styled(text: String) = ContentParser.parseMarkdown(text).filter { it.text.isNotEmpty() }

    @Test
    fun `single asterisks are italic, not literal`() {
        val spans = styled("*Hello*")
        assertEquals(listOf("Hello"), spans.map { it.text })
        assertTrue(spans.single().italic)
    }

    @Test
    fun `double asterisks are bold`() {
        val span = styled("**Hello**").single()
        assertEquals("Hello", span.text)
        assertTrue(span.bold)
    }

    @Test
    fun `mixed text keeps plain parts`() {
        val spans = styled("say *hi* now")
        assertEquals("say hi now", spans.joinToString("") { it.text })
        assertTrue(spans.first { it.text == "hi" }.italic)
    }
}
