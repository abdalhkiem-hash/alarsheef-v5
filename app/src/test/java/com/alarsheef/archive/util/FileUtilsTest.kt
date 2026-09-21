package com.alarsheef.archive.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileUtilsTest {

    @Test
    fun extensionOf_lowercasesAndStripsDot() {
        assertEquals("jpg", FileUtils.extensionOf("PHOTO.JPG"))
        assertEquals("pdf", FileUtils.extensionOf("doc.PDF"))
        assertEquals("", FileUtils.extensionOf("noextension"))
    }

    @Test
    fun isImage_recognizesSupportedFormats() {
        assertTrue(FileUtils.isImage("photo.jpg"))
        assertTrue(FileUtils.isImage("photo.PNG"))
        assertTrue(FileUtils.isImage("photo.webp"))
        assertFalse(FileUtils.isImage("doc.pdf"))
        assertFalse(FileUtils.isImage("archive.rar"))
    }

    @Test
    fun isPdf_recognizesPdfOnly() {
        assertTrue(FileUtils.isPdf("file.pdf"))
        assertFalse(FileUtils.isPdf("file.txt"))
        assertFalse(FileUtils.isPdf("photo.jpg"))
    }

    @Test
    fun monthArabicName_returnsCorrectNames() {
        assertEquals("يناير", FileUtils.monthArabicName(1))
        assertEquals("مارس", FileUtils.monthArabicName(3))
        assertEquals("ديسمبر", FileUtils.monthArabicName(12))
        assertEquals("شهر 13", FileUtils.monthArabicName(13)) // خارج النطاق — قيمة احتياطية
        assertEquals("شهر 0", FileUtils.monthArabicName(0))
    }

    @Test
    fun twoDigits_padsWithZero() {
        assertEquals("01", FileUtils.twoDigits(1))
        assertEquals("09", FileUtils.twoDigits(9))
        assertEquals("10", FileUtils.twoDigits(10))
        assertEquals("31", FileUtils.twoDigits(31))
    }

    @Test
    fun today_returnsValidTriple() {
        val (year, month, day) = FileUtils.today()
        assertTrue(year in 2000..2100)
        assertTrue(month in 1..12)
        assertTrue(day in 1..31)
    }

    @Test
    fun dayFolder_buildsYearMonthDayStructure() {
        val root = File(System.getProperty("java.io.tmpdir"), "arsheef_test_${System.nanoTime()}")
        val folder = FileUtils.dayFolder(root, 2026, 9, 5)
        assertEquals("archive", folder.parentFile?.parentFile?.parentFile?.name)
        assertEquals(File(root, "archive/2026/09/05"), folder)
        assertTrue(folder.isDirectory)
        folder.deleteRecursively()
    }

    @Test
    fun uniqueName_incrementsOnConflict() {
        val dir = File(System.getProperty("java.io.tmpdir"), "arsheef_test_${System.nanoTime()}")
        dir.mkdirs()
        val first = FileUtils.uniqueName("IMG", "jpg", dir)
        assertEquals("IMG.jpg", first)
        File(dir, first).writeBytes(byteArrayOf(1)) // الأول يُخزَّن فعلًا
        val second = FileUtils.uniqueName("IMG", "jpg", dir)
        assertEquals("IMG_1.jpg", second)
        assertNotEquals(first, second)
        dir.deleteRecursively()
    }

    @Test
    fun sha256Of_isDeterministicAndNonEmpty() {
        val f = File(System.getProperty("java.io.tmpdir"), "arsheef_test_${System.nanoTime()}.bin")
        f.writeBytes("content".toByteArray())
        val a = FileUtils.sha256Of(f)
        val b = FileUtils.sha256Of(f)
        assertEquals(a, b)
        assertEquals(64, a.length)
        f.delete()
    }
}