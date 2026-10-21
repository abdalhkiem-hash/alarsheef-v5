package com.alarsheef.archive.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRetentionTest {

    @Test
    fun keepsTwoNewestBackupsAndIgnoresOtherFiles() {
        val names = listOf(
            "notes.txt",
            null,
            "alarsheef-backup-100.zip",
            "alarsheef-backup-300.zip",
            "photo.jpg",
            "alarsheef-backup-200.zip",
            "alarsheef-backup-abc.zip"
        )
        assertEquals(
            listOf("alarsheef-backup-100.zip"),
            LocalBackupWorker.namesToDelete(names)
        )
    }

    @Test
    fun fewerThanTwoBackupsDeletesNothing() {
        assertTrue(LocalBackupWorker.namesToDelete(listOf("alarsheef-backup-5.zip")).isEmpty())
        assertTrue(
            LocalBackupWorker.namesToDelete(
                listOf("alarsheef-backup-2.zip", "alarsheef-backup-9.zip")
            ).isEmpty()
        )
    }
}
