package com.example.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import com.example.domain.AccessibilityConsent
import com.example.domain.AppSafety
import com.example.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.json.JSONObject
import java.io.*
import java.time.Instant

object LocalDataTransfer {
    private val booleanSettings = setOf("strict_mode", "sarcastic_mode", "use_blurred_background")

    suspend fun exportCsv(context: Context, output: OutputStream) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).dao()
        CSVPrinter(OutputStreamWriter(output, Charsets.UTF_8), CSVFormat.RFC4180).use { csv ->
            csv.printRecord("timestamp_utc", "package_name", "app_name", "recorded_seconds", "action", "event_id")
            historyPages(dao) { page ->
                page.forEach { row -> csv.printRecord(Instant.ofEpochMilli(row.startTime).toString(), spreadsheetText(row.packageName),
                    spreadsheetText(row.appName), row.durationSeconds, spreadsheetText(row.actionTaken), spreadsheetText(row.eventId.orEmpty())) }
            }
        }
    }

    fun spreadsheetText(value: String): String = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
        value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value

    suspend fun backup(context: Context, password: CharArray, database: AppDatabase = AppDatabase.getDatabase(context)): ByteArray = withContext(Dispatchers.IO) {
        val dao = database.dao()
        val preferences = context.getSharedPreferences(AccessibilityConsent.PREFS_NAME, Context.MODE_PRIVATE)
        val buffer = ByteArrayOutputStream()
        val limited = object : FilterOutputStream(buffer) {
            private var count = 0L
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                count += length
                require(count <= BackupEncryption.MAX_BYTES) { "Backup exceeds 32 MB. Export history as CSV instead." }
                out.write(bytes, offset, length)
            }
            override fun write(value: Int) {
                count++
                require(count <= BackupEncryption.MAX_BYTES) { "Backup exceeds 32 MB. Export history as CSV instead." }
                out.write(value)
            }
        }
        JsonWriter(OutputStreamWriter(limited, Charsets.UTF_8)).use { writer ->
            writer.beginObject().name("version").value(1)
            writer.name("rules").value(FocusSettings.encode(FocusSettings.configuration.value))
            writer.name("preferences").beginObject()
            booleanSettings.forEach { writer.name(it).value(preferences.getBoolean(it, false)) }
            writer.name("timer_mode").value(preferences.getInt("timer_mode", SessionManager.TIMER_MODE_CLEAR_ON_LOCK))
            writer.endObject()
            writer.name("apps").beginArray()
            val apps = dao.getAllMonitoredAppsFlow().first()
            apps.forEach { app ->
                writer.beginObject().name("package").value(app.packageName).name("name").value(app.appName)
                    .name("enabled").value(app.isEnabled).name("limit").value(app.limitMinutes).name("quota").value(app.dailyQuotaMinutes).endObject()
            }
            writer.endArray().name("history").beginArray()
            historyPages(dao) { page -> page.forEach { row ->
                writer.beginObject().name("package").value(row.packageName).name("name").value(row.appName)
                    .name("time").value(row.startTime).name("seconds").value(row.durationSeconds)
                    .name("action").value(row.actionTaken).name("event").value(row.eventId).endObject()
            } }
            writer.endArray().name("quotaDay").value(java.time.LocalDate.now().toEpochDay()).name("quotas").beginObject()
            apps.forEach { writer.name(it.packageName).value(SessionManager.getQuotaConsumedSecondsToday(it.packageName)) }
            writer.endObject().endObject()
        }
        val plain = buffer.toByteArray()
        try { BackupEncryption.encrypt(plain, password) } finally { plain.fill(0) }
    }

    suspend fun restore(context: Context, encrypted: ByteArray, password: CharArray, database: AppDatabase = AppDatabase.getDatabase(context)) = withContext(Dispatchers.IO) {
        val plain = BackupEncryption.decrypt(encrypted, password)
        try {
            JsonReader(InputStreamReader(ByteArrayInputStream(plain), Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                require(reader.nextName() == "version" && reader.nextInt() == 1)
                require(reader.nextName() == "rules")
                val rules = FocusSettings.decode(reader.nextString())
                require(reader.nextName() == "preferences")
                val savedPreferences = readObject(reader)
                require(savedPreferences.keys().asSequence().all { it in booleanSettings || it == "timer_mode" })
                val timerMode = savedPreferences.getInt("timer_mode")
                require(timerMode == SessionManager.TIMER_MODE_CLEAR_ON_LOCK || timerMode == SessionManager.TIMER_MODE_PERSISTENT)
                booleanSettings.forEach { savedPreferences.getBoolean(it) }
                withContext(Dispatchers.Main) { SessionManager.prepareForRestore() }
                val quotas = mutableMapOf<String, Int>()
                var quotaDay = 0L
                database.withTransaction {
                    val dao = database.dao()
                    require(reader.nextName() == "apps")
                    reader.beginArray()
                    dao.clearMonitoredApps()
                    val seen = mutableSetOf<String>()
                    while (reader.hasNext()) {
                        val app = readObject(reader)
                        val packageName = app.getString("package")
                        val name = app.getString("name")
                        require(packageName.isNotBlank() && packageName.length <= 255 && name.length <= 512 && seen.add(packageName) && seen.size <= 1000)
                        val quota = app.getInt("quota")
                        val limit = app.getInt("limit")
                        require(quota in 0..180 && limit in 0..180)
                        if (!AppSafety.isProtected(packageName, context.packageName)) dao.insertMonitoredApp(MonitoredApp(packageName, name, app.getBoolean("enabled"), limit, quota))
                    }
                    reader.endArray()
                    require(reader.nextName() == "history")
                    reader.beginArray()
                    dao.clearHistory()
                    val page = mutableListOf<SessionHistory>()
                    while (reader.hasNext()) {
                        val row = readObject(reader)
                        val packageName = row.getString("package")
                        val name = row.getString("name")
                        val seconds = row.getInt("seconds")
                        val time = row.getLong("time")
                        val action = row.getString("action")
                        val event = if (row.isNull("event")) null else row.getString("event")
                        require(packageName.isNotBlank() && packageName.length <= 255 && name.length <= 512)
                        require(seconds >= 0 && time >= 0 && action.length <= 64 && (event?.length ?: 0) <= 255)
                        page.add(SessionHistory(packageName = packageName, appName = name, startTime = time, durationSeconds = seconds, actionTaken = action, eventId = event))
                        if (page.size == 500) { dao.insertSessions(page); page.clear() }
                    }
                    if (page.isNotEmpty()) dao.insertSessions(page)
                    reader.endArray()
                    require(reader.nextName() == "quotaDay")
                    quotaDay = reader.nextLong()
                    require(reader.nextName() == "quotas")
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val packageName = reader.nextName()
                        val seconds = reader.nextInt()
                        require(packageName in seen && seconds >= 0 && packageName !in quotas)
                        quotas[packageName] = seconds
                    }
                    reader.endObject()
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
                withContext(Dispatchers.Main) {
                    FocusSettings.update(rules)
                    val preferences = context.getSharedPreferences(AccessibilityConsent.PREFS_NAME, Context.MODE_PRIVATE)
                    val editor = preferences.edit()
                    booleanSettings.forEach { editor.putBoolean(it, savedPreferences.getBoolean(it)) }
                    if (quotaDay == java.time.LocalDate.now().toEpochDay()) quotas.forEach { (packageName, seconds) ->
                        editor.putLong("quota_day_$packageName", quotaDay)
                        editor.putInt("quota_used_$packageName", maxOf(seconds, SessionManager.getQuotaConsumedSecondsToday(packageName)))
                    }
                    editor.apply()
                    SessionManager.setTimerMode(timerMode)
                    SessionManager.setStrictModeEnabled(savedPreferences.getBoolean("strict_mode"))
                    SessionManager.usageRevision.value++
                    com.example.service.NudgeWidgetProvider.triggerUpdate(context)
                }
            }
        } finally { plain.fill(0) }
    }

    private suspend fun historyPages(dao: ScreenGuardDao, consume: (List<SessionHistory>) -> Unit) {
        val lastId = dao.getLastHistoryId()
        var after = 0
        while (after < lastId) {
            val page = dao.getHistoryPage(after, lastId)
            if (page.isEmpty()) break
            consume(page)
            after = page.last().id
        }
    }

    private fun readObject(reader: JsonReader): JSONObject {
        val result = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(!result.has(name) && name.length <= 255)
            val value: Any = when (reader.peek()) {
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NUMBER -> reader.nextLong()
                JsonToken.STRING -> reader.nextString().also { require(it.length <= 1024) }
                JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                else -> throw IllegalArgumentException("Unsupported backup value")
            }
            result.put(name, value)
        }
        reader.endObject()
        return result
    }
}