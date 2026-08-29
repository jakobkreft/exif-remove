// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import si.jakobkreft.exifremove.data.RuleAction
import si.jakobkreft.exifremove.data.Template
import java.io.File

/**
 * End-to-end engine tests on a real JPEG (tiny fixture + EXIF written by
 * ExifInterface itself), run under Robolectric so the full ExifInterface
 * read/write path is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExifProcessorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var photo: File

    @Before
    fun preparePhoto() {
        photo = tmp.newFile("original.jpg")
        javaClass.classLoader!!.getResourceAsStream("tiny.jpg")!!.use { ins ->
            photo.outputStream().use { outs -> ins.copyTo(outs) }
        }
        ExifInterface(photo.absolutePath).apply {
            setLatLong(43.66407, 15.62384)
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "41/1")
            setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2026:07:16")
            setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "07:21:46")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:07:16 09:22:50")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00")
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            setAttribute(ExifInterface.TAG_MODEL, "Pixel 10 Pro")
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008")
            setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
            saveAttributes()
        }
    }

    private fun clean(template: Template): ExifInterface {
        val out = tmp.newFile("cleaned.jpg")
        assertNotNull(ExifProcessor.cleanFile(photo, out, template))
        return ExifInterface(out.absolutePath)
    }

    /** The report the engine produced for the last [clean] of [photo]. */
    private fun report(template: Template): CleaningReport {
        val out = tmp.newFile("reported.jpg")
        return ExifProcessor.cleanFile(photo, out, template)!!
    }

    @Test
    fun `remove everything leaves no exif at all`() {
        val exif = clean(Template(id = "t", name = "t"))
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
        // ExifInterface synthesizes "0" (undefined) when the tag is absent
        assertEquals(0, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
    }

    @Test
    fun `remove location only removes gps and keeps the rest identical`() {
        val original = ExifInterface(photo.absolutePath)
        val exif = clean(
            Template(
                id = "t", name = "t",
                gps = RuleAction.REMOVE,
                dateTime = RuleAction.KEEP,
                cameraInfo = RuleAction.KEEP,
                otherExif = RuleAction.KEEP,
            )
        )
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
        // GPS date/time stamps are time information: the KEEP date rule keeps them
        assertEquals("2026:07:16", exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP))
        // Kept values are byte-identical, not re-encoded
        assertEquals(
            original.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
        )
        assertEquals("2026:07:16 09:22:50", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("+02:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals("Pixel 10 Pro", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("6", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
    }

    @Test
    fun `keep location only still removes gps date and time stamps`() {
        val exif = clean(
            Template(
                id = "t", name = "t",
                gps = RuleAction.KEEP,
                dateTime = RuleAction.REMOVE,
                cameraInfo = RuleAction.REMOVE,
                otherExif = RuleAction.REMOVE,
            )
        )
        assertNotNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP))
    }

    @Test
    fun `scramble randomizes location and date and drops the rest`() {
        val exif = clean(
            Template(
                id = "t", name = "t",
                gps = RuleAction.RANDOMIZE,
                dateTime = RuleAction.RANDOMIZE,
            )
        )
        val coords = exif.latLong
        assertNotNull(coords)
        assertFalse(kotlin.math.abs(coords!![0] - 43.66407) < 0.0001)
        val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        assertNotNull(date)
        assertFalse(date == "2026:07:16 09:22:50")
        // Timezone offset and GPS fix time must not survive a randomized date
        assertNull(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        // ExifInterface synthesizes "0" (undefined) when the tag is absent
        assertEquals(0, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
    }

    @Test
    fun `scramble writes a location into a photo that had none`() {
        val noGps = tmp.newFile("nogps.jpg")
        javaClass.classLoader!!.getResourceAsStream("tiny.jpg")!!.use { ins ->
            noGps.outputStream().use { outs -> ins.copyTo(outs) }
        }
        ExifInterface(noGps.absolutePath).apply {
            // Junk GPS block without coordinates, as some cameras write
            setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "00:00:00")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:07:16 12:23:53")
            saveAttributes()
        }
        val out = tmp.newFile("nogps_cleaned.jpg")
        ExifProcessor.cleanFile(
            noGps, out,
            Template(
                id = "t", name = "t",
                gps = RuleAction.RANDOMIZE,
                dateTime = RuleAction.RANDOMIZE,
            ),
        )
        val entries = ImageMetadataReader.read(out)
        // The new random location must be visible to the viewer
        assertTrue(entries.any { it.name == "GPS position" && it.value.contains("″") })
        assertTrue(entries.none { it.name == "GPS Time Stamp" })
    }

    @Test
    fun `surgical clean does not resurrect parser defaults`() {
        val out = tmp.newFile("cleaned2.jpg")
        ExifProcessor.cleanFile(
            photo, out,
            Template(
                id = "t", name = "t",
                gps = RuleAction.REMOVE,
                dateTime = RuleAction.KEEP,
                cameraInfo = RuleAction.KEEP,
                otherExif = RuleAction.KEEP,
            ),
        )
        // The viewer hides these, but they also must not be written into
        // the file when the original never stored them.
        val entries = ImageMetadataReader.read(out)
        assertTrue(entries.none { it.name == "Light Source" })
    }
}
