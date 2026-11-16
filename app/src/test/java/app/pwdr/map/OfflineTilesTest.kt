package app.pwdr.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTilesTest {
    @Test fun tileZoomIsOneFinerAndCappedAtSourceMax() {
        assertEquals(15, OfflineTiles.tileZoom(14))
        assertEquals(18, OfflineTiles.tileZoom(18))
        assertEquals(18, OfflineTiles.tileZoom(20))
    }

    @Test fun pointAreaIsOneTilePerZoom() {
        assertEquals(3, OfflineTiles.count(46.4, 7.8, 46.4, 7.8, 10, 12))
    }

    @Test fun moreZoomMeansMoreTiles() {
        val a = OfflineTiles.count(46.38, 7.75, 46.43, 7.85, 10, 14)
        val b = OfflineTiles.count(46.38, 7.75, 46.43, 7.85, 10, 15)
        assertTrue(b > a * 2)
    }

    @Test fun wholeSwitzerlandAtLowZoomIsSmall() {
        val c = OfflineTiles.count(45.8, 5.9, 47.8, 10.5, 5, 8)
        assertTrue(c.toString(), c in 1..200)
    }

    @Test fun sizeEstimateDependsOnMap() {
        assertTrue(OfflineTiles.estimateBytes(100, BaseMap.AERIAL, false) > OfflineTiles.estimateBytes(100, BaseMap.GREY, false))
        assertTrue(OfflineTiles.estimateBytes(100, BaseMap.COLOR, true) > OfflineTiles.estimateBytes(100, BaseMap.COLOR, false))
    }

    @Test fun downloadStyleHasOnlyChosenSources() {
        val color = SwisstopoStyle.downloadJson(BaseMap.COLOR, false)
        assertTrue(color.contains("pixelkarte-farbe"))
        assertTrue(!color.contains("swissimage") && !color.contains("hangneigung") && !color.contains("pixelkarte-grau"))
        val both = SwisstopoStyle.downloadJson(BaseMap.AERIAL, true)
        assertTrue(both.contains("swissimage") && both.contains("hangneigung"))
    }

    @Test fun downloadAssetsMatchTheCode() {
        for (base in BaseMap.entries) for (slope in listOf(false, true)) {
            val file = java.io.File("src/main/assets/offline/${base.key}${if (slope) "-slope" else ""}.json")
            assertTrue("fehlt: $file", file.exists())
            assertEquals(SwisstopoStyle.downloadJson(base, slope), file.readText(Charsets.UTF_8))
        }
    }
}
