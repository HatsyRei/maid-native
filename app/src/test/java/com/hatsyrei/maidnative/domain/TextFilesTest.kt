package com.hatsyrei.maidnative.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFilesTest {

    @Test
    fun `text mime types pass whatever the name`() {
        assertTrue(TextFiles.isText("notes", "text/plain"))
        assertTrue(TextFiles.isText("data.bin", "application/json; charset=utf-8"))
        assertTrue(TextFiles.isText("feed", "application/atom+xml"))
    }

    @Test
    fun `known extensions pass as octet-stream`() {
        for (name in listOf("app.log", "php.INI", "nginx.conf", "Cargo.toml", "a.b.yml")) {
            assertTrue(name, TextFiles.isText(name, "application/octet-stream"))
        }
        assertTrue(TextFiles.isText("server.log", null))
    }

    @Test
    fun `binaries and unknown extensions fail`() {
        assertFalse(TextFiles.isText("paper.pdf", "application/pdf"))
        assertFalse(TextFiles.isText("blob.bin", "application/octet-stream"))
        assertFalse(TextFiles.isText("Makefile", "application/octet-stream"))
        assertFalse(TextFiles.isText("photo.png", "image/png"))
    }
}
