package app.strata3d.recent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentFilesTest {

    private fun file(id: String, uri: String = "content://docs/$id", companions: List<CompanionFile> = emptyList(), opened: Long = 0L) =
        RecentFile(id, "$id.stl", uri, companions, "STL", 1234, 12, opened, persisted = true, thumbnail = "$id.png")

    @Test
    fun jsonRoundTripPreservesEverythingIncludingAwkwardText() {
        val entries = listOf(
            file("a", companions = listOf(CompanionFile("scene \"data\".bin", "content://x/b%20in\\n"))),
            RecentFile("b", "naïve\tname\n✓.glb", "content://y/1", emptyList(), "GLB", -1, 0, 99L, persisted = false, thumbnail = null),
        )
        val decoded = RecentFilesCodec.decode(RecentFilesCodec.encode(entries))
        assertEquals(RecentFilesCodec.Status.OK, decoded.status)
        assertEquals(entries, decoded.entries)
    }

    @Test
    fun unitChoiceIsStoredAsAnOptionalField() {
        val withUnit = file("u").copy(unit = app.strata3d.model.LengthUnit.INCH)
        val json = RecentFilesCodec.encode(listOf(withUnit, file("v")))
        assertTrue(json.contains("\"unit\":\"INCH\""))
        assertEquals(listOf(withUnit, file("v")), RecentFilesCodec.decode(json).entries)
        // Files written before units existed, or with a unit this version does not know, still load.
        val older = "{\"version\":1,\"entries\":[{\"id\":\"k\",\"uri\":\"content://k\",\"unit\":\"PARSEC\"}]}"
        val decoded = RecentFilesCodec.decode(older)
        assertEquals(RecentFilesCodec.Status.OK, decoded.status)
        assertEquals(null, decoded.entries.single().unit)
    }

    @Test
    fun damagedOrUnknownDataNeverThrows() {
        assertEquals(RecentFilesCodec.Status.EMPTY, RecentFilesCodec.decode(null).status)
        assertEquals(RecentFilesCodec.Status.EMPTY, RecentFilesCodec.decode("  ").status)
        assertEquals(RecentFilesCodec.Status.CORRUPT, RecentFilesCodec.decode("{\"version\":1,\"entries\":[{").status)
        assertEquals(RecentFilesCodec.Status.CORRUPT, RecentFilesCodec.decode("[1,2,3]").status)
        assertEquals(RecentFilesCodec.Status.NEWER_VERSION, RecentFilesCodec.decode("{\"version\":9,\"entries\":[]}").status)
        // Entries missing required fields are skipped, the rest are kept.
        val partial = RecentFilesCodec.decode("{\"version\":1,\"entries\":[{\"name\":\"x\"},{\"id\":\"k\",\"uri\":\"content://k\"}]}")
        assertEquals(listOf("k"), partial.entries.map { it.id })
    }

    @Test
    fun addPutsNewestFirstDeduplicatesAndKeepsFifteen() {
        var list = emptyList<RecentFile>()
        val dropped = ArrayList<RecentFile>()
        for (i in 1..17) {
            val (kept, gone) = RecentFiles.add(list, file("f$i", opened = i.toLong()))
            list = kept
            dropped += gone
        }
        assertEquals(RecentFiles.MAX_ENTRIES, list.size)
        assertEquals("f17", list.first().id)
        assertEquals("the oldest entries are dropped, in order", listOf("f1", "f2"), dropped.map { it.id })
        // Opening an existing document again moves it to the top without duplicating it.
        val (again, gone) = RecentFiles.add(list, file("f10", opened = 100))
        assertEquals("f10", again.first().id)
        assertEquals(RecentFiles.MAX_ENTRIES, again.size)
        assertEquals(1, again.count { it.id == "f10" })
        assertTrue(gone.isEmpty())
    }

    @Test
    fun reopeningReplacesTheOldEntry() {
        val old = file("old", uri = "content://moved/before")
        val other = file("other")
        val reselected = file("new", uri = "content://moved/after")
        val (kept, dropped) = RecentFiles.add(listOf(old, other), reselected, replacingId = "old")
        assertEquals(listOf("new", "other"), kept.map { it.id })
        assertEquals(listOf("old"), dropped.map { it.id })
    }

    @Test
    fun onlyUrisNoLongerUsedAreReleased() {
        val shared = "content://shared/bin"
        val a = file("a", companions = listOf(CompanionFile("x.bin", shared)))
        val b = file("b", companions = listOf(CompanionFile("x.bin", shared)))
        val releasable = RecentFiles.releasableUris(dropped = listOf(a), remaining = listOf(b))
        assertEquals(setOf(a.uri), releasable)
    }
}
