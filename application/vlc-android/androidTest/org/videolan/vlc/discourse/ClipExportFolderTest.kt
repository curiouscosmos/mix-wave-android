package org.videolan.vlc.discourse

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClipExportFolderTest {
    @Test fun createsAndReusesDiscourseFolderForMediaAndCaptions() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString())
        check(root.mkdirs())
        try {
            val parent = DocumentFile.fromFile(root)
            val name = clipDiscourseFolderName("ध्यान और प्रेम")
            val directory = ClipExportFolder.discourseDirectory(parent, name)
            File(File(root, name), "clip.mp4").writeText("video")
            File(File(root, name), "clip.srt").writeText("captions")
            val reused = ClipExportFolder.discourseDirectory(parent, name)
            assertEquals(directory.uri, reused.uri)
            assertEquals(setOf("clip.mp4", "clip.srt"), reused.listFiles().map { it.name }.toSet())
            assertEquals(1, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun existingFileIsNotOverwrittenByAFolder() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString())
        check(root.mkdirs())
        try {
            File(root, "Discourse").writeText("keep")
            try {
                ClipExportFolder.discourseDirectory(DocumentFile.fromFile(root), "Discourse")
                fail("A regular file cannot be used as an export folder")
            } catch (_: ClipExportFolderUnavailable) { }
            assertEquals("keep", File(root, "Discourse").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun folderNamesPreserveDiscourseTitlesAndStayWithinTheSelectedPath() {
        assertEquals("ध्यान और प्रेम", clipDiscourseFolderName("ध्यान और प्रेम"))
        assertEquals("Discourse", clipDiscourseFolderName(".."))
        assertEquals("Discourse", clipDiscourseFolderName("   "))
        assertEquals("A_B_C", clipDiscourseFolderName("A/B\\C"))
    }
}
