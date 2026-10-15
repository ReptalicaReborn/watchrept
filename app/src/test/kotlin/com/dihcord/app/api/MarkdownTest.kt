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

    @Test
    fun `double underscores underline, like Discord`() {
        val span = styled("__Hello__").single()
        assertEquals("Hello", span.text)
        assertTrue(span.underline)
        assertTrue(!span.bold)
    }

    @Test
    fun `single underscores are italic`() {
        val span = styled("_Hello_").single()
        assertEquals("Hello", span.text)
        assertTrue(span.italic)
    }

    @Test
    fun `underscores inside words are left alone`() {
        val spans = styled("snake_case_name")
        assertEquals("snake_case_name", spans.joinToString("") { it.text })
        assertTrue(spans.none { it.italic })
    }
}
