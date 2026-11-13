package com.alarsheef.archive.porter

import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueZipEntryNameTest {

    @Test
    fun firstUse_keepsTheName() {
        val used = HashSet<String>()
        assertEquals("files/2026/10/27/a.pdf", uniqueZipEntryName("files/2026/10/27/a.pdf", used))
    }

    @Test
    fun repeatedName_getsNumberBeforeExtension() {
        val used = HashSet<String>()
        val first = uniqueZipEntryName("files/2026/10/27/مستند.webp", used)
        val second = uniqueZipEntryName("files/2026/10/27/مستند.webp", used)
        val third = uniqueZipEntryName("files/2026/10/27/مستند.webp", used)
        assertEquals("files/2026/10/27/مستند.webp", first)
        assertEquals("files/2026/10/27/مستند (2).webp", second)
        assertEquals("files/2026/10/27/مستند (3).webp", third)
        assertEquals(3, used.size)
    }

    @Test
    fun dotInFolderName_isNotTakenAsExtension() {
        val used = HashSet<String>()
        uniqueZipEntryName("files/a.b/noext", used)
        assertEquals("files/a.b/noext (2)", uniqueZipEntryName("files/a.b/noext", used))
    }
}
