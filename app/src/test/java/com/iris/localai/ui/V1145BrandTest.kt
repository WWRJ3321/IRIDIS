package com.iris.localai.ui

import com.iris.localai.ui.settings.SettingsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.14.5: product renamed IRIDIS; D9 wordmark on the About page and the system splash. */
class V1145BrandTest {
    private fun res(p: String) = listOf(File("src/main/res/$p"), File("app/src/main/res/$p")).first { it.exists() }.readText()

    @Test fun appNameIsIridis() = assertTrue(res("values/strings.xml").contains("<string name=\"app_name\">IRIDIS</string>"))

    @Test fun versionLineUsesNewName() = assertEquals("IRIDIS 1.14.5-full", SettingsText.versionLine("1.14.5-full"))

    @Test fun splashCarriesWordmarkInBothThemes() {
        for (d in listOf("values", "values-night")) assertTrue(res("$d/themes.xml").contains("@drawable/iridis_splash_branding"))
        assertTrue(res("drawable-night/iridis_splash_branding.xml").contains("#FF5660F0"))
    }
}
