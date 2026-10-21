package com.saab.tv.data.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringCatalogCompletenessTest {
    @Test fun supportedLocalesCoverEveryDefaultStringAndPreserveFormatArguments() {
        val defaultCatalog = readCatalog("values")

        listOf("values-kn", "values-ml").forEach { directory ->
            val translatedCatalog = readCatalog(directory)
            assertEquals("$directory is missing default strings", defaultCatalog.keys, translatedCatalog.keys)

            val mismatchedFormats = defaultCatalog.keys.filter { key ->
                formatArguments(defaultCatalog.getValue(key)) != formatArguments(translatedCatalog.getValue(key))
            }
            assertTrue("$directory has mismatched format arguments: $mismatchedFormats", mismatchedFormats.isEmpty())
        }
    }

    private fun readCatalog(directory: String): Map<String, String> {
        val file = listOf(
            File("src/main/res/$directory/strings.xml"),
            File("app/src/main/res/$directory/strings.xml")
        ).firstOrNull(File::isFile) ?: error("Could not find $directory/strings.xml from the test working directory")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val key = element.getAttribute("name")
                assertTrue("Duplicate string key $key in $directory", put(key, element.textContent) == null)
            }
        }
    }

    private fun formatArguments(value: String): List<String> =
        FORMAT_ARGUMENT.findAll(value).map { it.value }.sorted().toList()

    private companion object {
        val FORMAT_ARGUMENT = Regex("%(?:[0-9]+\\$)?[-#+ 0,(]*[0-9]*(?:\\.[0-9]+)?[a-zA-Z]")
    }
}
