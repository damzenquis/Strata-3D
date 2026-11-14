package app.strata3d.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelResourcesTest {

    @Test
    fun normalizeDecodesPercentEscapesAndDotSlash() {
        assertEquals("buffers/scene data.bin", ModelResources.normalize("./buffers/scene%20data.bin"))
        assertEquals("café.bin", ModelResources.normalize("caf%C3%A9.bin"))
        assertEquals("a/b.bin", ModelResources.normalize("a\\b.bin"))
    }

    @Test
    fun malformedEscapesAreKeptLiterally() {
        assertEquals("100%.bin", ModelResources.normalize("100%.bin"))
        assertEquals("x%zz.bin", ModelResources.normalize("x%zz.bin"))
    }

    @Test
    fun fileNameIsLastSegment() {
        assertEquals("scene data.bin", ModelResources.fileName("data/scene%20data.bin"))
    }
}
