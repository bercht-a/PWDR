package app.pwdr.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    private fun rel(
        tag: String,
        pre: Boolean = true,
        draft: Boolean = false,
        apk: String? = "https://github.com/bercht-a/PWDR/releases/download/$tag/pwdr.apk",
    ) = ReleaseDto(
        tag = tag,
        prerelease = pre,
        draft = draft,
        htmlUrl = "https://github.com/bercht-a/PWDR/releases/tag/$tag",
        assets = listOfNotNull(
            apk?.let { ReleaseAsset("pwdr-${tag.removePrefix("v")}.apk", it) },
            ReleaseAsset("x.sha256", "https://github.com/x.sha256"),
        ),
    )

    @Test fun parsesTagsAndRejectsGarbage() {
        assertEquals(listOf(0, 1, 0), v("v0.1.0-alpha.8").core)
        assertEquals(listOf("alpha", "8"), v("0.1.0-alpha.8").pre)
        assertEquals(listOf(1, 2, 3), v("1.2.3+build5").core)
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("nightly"))
        assertNull(AppVersion.parse("1.x.0"))
    }

    @Test fun alphaNumbersCompareNumerically() {
        assertTrue(v("0.1.0-alpha.10") > v("0.1.0-alpha.9"))
        assertTrue(v("0.1.0-alpha.9") > v("0.1.0-alpha.8"))
        assertEquals(0, v("0.1.0-alpha.8").compareTo(v("v0.1.0-alpha.8")))
    }

    @Test fun orderOfPhasesAndFinalRelease() {
        assertTrue(v("0.1.0-beta.1") > v("0.1.0-alpha.9"))
        assertTrue(v("0.1.0-rc.1") > v("0.1.0-beta.2"))
        assertTrue(v("0.1.0") > v("0.1.0-rc.1"))
        assertTrue(v("0.2.0-alpha.1") > v("0.1.0"))
        assertTrue(v("0.1.0-alpha") < v("0.1.0-alpha.1"))
        assertEquals(0, v("1.0").compareTo(v("1.0.0")))
    }

    @Test fun offersNewestNewerRelease() {
        val u = UpdateLogic.newer("0.1.0-alpha.8", listOf(rel("v0.1.0-alpha.9"), rel("v0.1.0-alpha.10"), rel("v0.1.0-alpha.8")))
        assertNotNull(u)
        assertEquals("0.1.0-alpha.10", u!!.version)
        assertTrue(u.apkUrl.endsWith("pwdr.apk"))
        assertEquals(u.apkUrl, u.target)
    }

    @Test fun nothingWhenCurrentOrAhead() {
        assertNull(UpdateLogic.newer("0.1.0-alpha.8", listOf(rel("v0.1.0-alpha.8"), rel("v0.1.0-alpha.7"))))
        assertNull(UpdateLogic.newer("0.1.0-alpha.9", listOf(rel("v0.1.0-alpha.8"))))
        assertNull(UpdateLogic.newer("0.1.0-alpha.8", emptyList()))
    }

    @Test fun draftsAndUnreadableTagsAreSkipped() {
        assertNull(UpdateLogic.newer("0.1.0-alpha.8", listOf(rel("v0.1.0-alpha.9", draft = true), rel("irgendwas"))))
    }

    @Test fun stableUsersGetNoPrereleases() {
        assertNull(UpdateLogic.newer("0.1.0", listOf(rel("v0.2.0-alpha.1", pre = true))))
        assertNotNull(UpdateLogic.newer("0.1.0", listOf(rel("v0.2.0", pre = false))))
    }

    @Test fun unknownInstalledVersionOffersNothing() {
        assertNull(UpdateLogic.newer(null, listOf(rel("v9.0.0", pre = false))))
        assertNull(UpdateLogic.newer("dev", listOf(rel("v9.0.0", pre = false))))
    }

    @Test fun fallsBackToReleasePageWithoutApk() {
        val u = UpdateLogic.newer("0.1.0-alpha.8", listOf(rel("v0.1.0-alpha.9", apk = null)))!!
        assertEquals("", u.apkUrl)
        assertTrue(u.target.contains("/releases/tag/"))
    }

    @Test fun foreignHostsAreNeverOpened() {
        val evil = ReleaseDto(tag = "v9.9.9-alpha.1", prerelease = true, htmlUrl = "https://evil.example/x")
        assertNull(UpdateLogic.newer("0.1.0-alpha.8", listOf(evil)))
        val apkEvil = rel("v0.1.0-alpha.9", apk = "https://evil.example/pwdr.apk")
        val u = UpdateLogic.newer("0.1.0-alpha.8", listOf(apkEvil))!!
        assertEquals("", u.apkUrl)
    }

    @Test fun parsesGithubJsonAndIgnoresUnknownFields() {
        val body = """[{"tag_name":"v0.1.0-alpha.9","name":"PWDR 0.1.0-alpha.9","draft":false,"prerelease":true,
            "html_url":"https://github.com/bercht-a/PWDR/releases/tag/v0.1.0-alpha.9","author":{"login":"x"},
            "assets":[{"name":"pwdr-0.1.0-alpha.9.apk","browser_download_url":"https://github.com/bercht-a/PWDR/releases/download/v0.1.0-alpha.9/pwdr-0.1.0-alpha.9.apk","size":1}]}]"""
        val list = UpdateLogic.parse(body)!!
        assertEquals(1, list.size)
        assertEquals("0.1.0-alpha.9", UpdateLogic.newer("0.1.0-alpha.8", list)!!.version)
        assertNull(UpdateLogic.parse("<html>"))
        assertNull(UpdateLogic.parse("""{"message":"rate limit"}"""))
    }

    @Test fun checkIsDueOncePerDay() {
        val day = 24 * 3_600_000L
        assertTrue(UpdateLogic.isDue(0, 1000))
        assertFalse(UpdateLogic.isDue(1000, 1000 + day - 1))
        assertTrue(UpdateLogic.isDue(1000, 1000 + day))
        assertTrue("Uhr zurückgestellt", UpdateLogic.isDue(5000, 1000))
    }
}
