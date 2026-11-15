package app.pwdr.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwisstopoStyleTest {
    private fun visibility(json: String): Map<String, String> =
        Json.parseToJsonElement(json).jsonObject["layers"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["id"]!!.jsonPrimitive.content to (o["layout"]?.jsonObject?.get("visibility")?.jsonPrimitive?.content ?: "visible")
        }

    @Test fun styleIsValidJsonWithAllLayers() {
        val v = visibility(SwisstopoStyle.json())
        assertTrue(v.keys.containsAll(listOf("background", "base-color", "base-aerial", "base-grey", SwisstopoStyle.SLOPE_LAYER)))
    }

    @Test fun onlyChosenBaseIsVisible() {
        for (b in BaseMap.entries) {
            val v = visibility(SwisstopoStyle.json(b, false))
            BaseMap.entries.forEach { o -> assertEquals("${b.key}/${o.key}", if (o == b) "visible" else "none", v[o.layerId]) }
            assertEquals("none", v[SwisstopoStyle.SLOPE_LAYER])
        }
    }

    @Test fun slopeOverlayCanBeOn() {
        assertEquals("visible", visibility(SwisstopoStyle.json(BaseMap.AERIAL, true))[SwisstopoStyle.SLOPE_LAYER])
    }

    @Test fun defaultStyleIsColorMap() {
        assertEquals("visible", visibility(SwisstopoStyle.json)["base-color"])
        assertEquals(BaseMap.COLOR, BaseMap.of("quatsch"))
        assertEquals(BaseMap.AERIAL, BaseMap.of("aerial"))
    }

    @Test fun attributionStaysOnEverySource() {
        val sources = Json.parseToJsonElement(SwisstopoStyle.json()).jsonObject["sources"]!!.jsonObject
        sources.values.forEach { assertEquals("© swisstopo", it.jsonObject["attribution"]!!.jsonPrimitive.content) }
    }
}
