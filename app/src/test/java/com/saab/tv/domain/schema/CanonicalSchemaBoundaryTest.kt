package com.saab.tv.domain.schema

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSchemaBoundaryTest {
    @Test fun providerDtosAreConfinedToTheExplicitCanonicalCompatibilityAdapter() {
        val sourceRoot = projectRoot().resolve("app/src/main/java/com/saab/tv/domain/schema")
        assertTrue("Canonical schema source directory must exist", sourceRoot.isDirectory)

        sourceRoot.listFiles { file -> file.extension == "kt" }.orEmpty()
            .filterNot { it.name == "CanonicalTitleProjection.kt" }
            .forEach { source ->
                val sourceText = source.readText()
                assertFalse(
                    "Provider DTO leaked into canonical schema: ${source.name}",
                    sourceText.contains("com.saab.tv.data.model.stremio") ||
                        sourceText.contains("com.saab.tv.data.tmdb") ||
                        sourceText.contains("com.saab.tv.data.trakt")
                )
            }

        val homeRows = projectRoot().resolve("app/src/main/java/com/saab/tv/domain/HubModels.kt").readText()
        assertFalse("Home card state must use canonical catalog projections", homeRows.contains("data.model.stremio.MetaItem"))
        val legacyPage = projectRoot().resolve("app/src/main/java/com/saab/tv/domain/HomeRow.kt").readText()
        assertTrue("Legacy row DTO must document its adapter boundary", legacyPage.contains("Temporary provider-adapter page payload"))

        val uiRoot = projectRoot().resolve("app/src/main/java/com/saab/tv/ui")
        assertTrue("UI source directory must exist", uiRoot.isDirectory)
        uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { source ->
            val sourceText = source.readText()
            assertFalse(
                "TMDB preview DTO leaked past its adapter: ${source.relativeTo(projectRoot())}",
                sourceText.contains("TmdbMetaPreview") ||
                    sourceText.contains("com.saab.tv.data.model.tmdb") ||
                    sourceText.contains("com.saab.tv.data.remote.Tmdb")
            )
        }
    }

    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            if (directory.resolve("app/src/main/java/com/saab/tv/domain/schema").isDirectory) return directory
            directory = directory.parentFile
        }
        error("Could not locate the application source root from user.dir")
    }
}
