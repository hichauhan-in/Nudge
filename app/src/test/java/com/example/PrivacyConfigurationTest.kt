package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PrivacyConfigurationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun accessibilityCannotRetrieveScreenContent() {
        val parser = context.resources.getXml(R.xml.accessibility_service_config)
        while (parser.eventType != XmlPullParser.START_TAG) parser.next()
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("false", parser.getAttributeValue(android, "canRetrieveWindowContent"))
        assertEquals("false", parser.getAttributeValue(android, "isAccessibilityTool"))
        parser.close()
    }

    @Test
    fun allBackupAndTransferDomainsAreExcluded() {
        val expected = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        listOf(R.xml.backup_rules, R.xml.data_extraction_rules).forEach { resource ->
            val parser = context.resources.getXml(resource)
            val domains = mutableListOf<String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    assertEquals(".", parser.getAttributeValue(null, "path"))
                    domains.add(parser.getAttributeValue(null, "domain"))
                }
                parser.next()
            }
            parser.close()
            assertEquals(expected, domains.toSet())
            assertEquals(if (resource == R.xml.backup_rules) 9 else 18, domains.size)
        }
    }
}