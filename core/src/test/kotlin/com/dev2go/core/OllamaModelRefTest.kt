package com.dev2go.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OllamaModelRefTest {

    @Test
    fun `parses name and tag`() {
        val ref = OllamaModelRef.parse("qwen3:0.6b")
        assertEquals("qwen3", ref?.name)
        assertEquals("0.6b", ref?.tag)
        assertEquals("qwen3:0.6b", ref.toString())
    }

    @Test
    fun `a bare name gets the latest tag`() {
        assertEquals("llama3:latest", OllamaModelRef.parse("llama3").toString())
    }

    @Test
    fun `keeps a registry prefix so same-named models do not collide`() {
        val ref = OllamaModelRef.parse("library/qwen3:8b")
        assertEquals("library/qwen3", ref?.name)
        assertEquals("8b", ref?.tag)
    }

    @Test
    fun `strips the ollama scheme a manifest may carry`() {
        assertEquals("qwen3:0.6b", OllamaModelRef.parse("ollama/qwen3:0.6b").toString())
    }

    @Test
    fun `trims surrounding whitespace from a downloaded manifest`() {
        assertEquals("qwen3:0.6b", OllamaModelRef.parse("  qwen3:0.6b\n").toString())
    }

    @Test
    fun `rejects input that is not a usable reference`() {
        // A catalog lists whatever a manifest contained; one bad entry must not throw and
        // take the listing down with it.
        assertNull(OllamaModelRef.parse(""))
        assertNull(OllamaModelRef.parse("   "))
        assertNull(OllamaModelRef.parse(":8b"))
        assertNull(OllamaModelRef.parse("qwen3:"))
    }

    @Test
    fun `a second colon makes the reference invalid rather than shifting the split`() {
        // The name owns the first colon; a tag containing one cannot be represented, and
        // silently re-splitting on the last colon would produce a name that is itself
        // malformed instead of reporting the input as bad.
        assertNull(OllamaModelRef.parse("qwen3:a:b"))
    }

    @Test
    fun `a registry path with slashes is not confused for a tag`() {
        val ref = OllamaModelRef.parse("registry.example.com/library/qwen3:8b")
        assertEquals("registry.example.com/library/qwen3", ref?.name)
        assertEquals("8b", ref?.tag)
    }
}