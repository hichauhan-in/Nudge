package com.example.data

import android.content.Context
import com.example.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

object FocusSettings {
    private var context: Context? = null
    private val mutable = MutableStateFlow(FocusConfiguration())
    val configuration: StateFlow<FocusConfiguration> = mutable

    fun init(context: Context) {
        val appContext = context.applicationContext
        if (this.context === appContext) return
        this.context = appContext
        val stored = appContext.getSharedPreferences("focus_rules", Context.MODE_PRIVATE).getString("configuration", null)
        mutable.value = try { stored?.let(::decode) ?: FocusConfiguration() } catch (_: Exception) { FocusConfiguration() }
    }

    fun update(configuration: FocusConfiguration) {
        val valid = configuration.validated()
        val preferences = checkNotNull(context).getSharedPreferences("focus_rules", Context.MODE_PRIVATE)
        preferences.edit().putString("configuration", encode(valid)).apply()
        mutable.value = valid
    }

    fun updateRule(packageName: String, rule: AppRule) = update(mutable.value.copy(rules = mutable.value.rules + (packageName to rule)))

    fun rememberDuration(packageName: String, minutes: Int) {
        val rule = mutable.value.rule(packageName)
        if (rule.rememberDuration && minutes in 1..60 && rule.preferredMinutes != minutes) updateRule(packageName, rule.copy(preferredMinutes = minutes))
    }

    fun removeApp(packageName: String) {
        val current = mutable.value
        update(current.copy(rules = current.rules - packageName,
            budgets = current.budgets.map { it.copy(packages = it.packages - packageName) }.filter { it.packages.isNotEmpty() }))
    }

    fun encode(config: FocusConfiguration): String = JSONObject().apply {
        put("version", 1)
        put("goal", config.weeklyGoalMinutes)
        put("profiles", JSONArray().apply {
            config.profiles.forEach { profile -> put(JSONObject().apply {
                put("id", profile.id); put("name", profile.name)
                put("days", JSONArray(profile.schedule.days.sorted()))
                put("start", profile.schedule.startMinute); put("end", profile.schedule.endMinute)
            }) }
        })
        put("rules", JSONObject().apply {
            config.rules.forEach { (packageName, rule) -> put(packageName, JSONObject().apply {
                put("minutes", rule.preferredMinutes); put("remember", rule.rememberDuration)
                put("tone", rule.tone.name); put("profile", rule.profileId ?: JSONObject.NULL)
                put("extensions", rule.maxExtensions); put("cooldown", rule.cooldownMinutes)
            }) }
        })
        put("budgets", JSONArray().apply {
            config.budgets.forEach { budget -> put(JSONObject().apply {
                put("id", budget.id); put("name", budget.name); put("minutes", budget.minutes)
                put("packages", JSONArray(budget.packages.sorted()))
            }) }
        })
    }.toString()

    fun decode(json: String): FocusConfiguration {
        val root = JSONObject(json)
        require(root.getInt("version") == 1)
        val profiles = root.getJSONArray("profiles")
        val rules = root.getJSONObject("rules")
        val budgets = root.optJSONArray("budgets") ?: JSONArray()
        return FocusConfiguration(
            profiles = (0 until profiles.length()).map { index ->
                val profile = profiles.getJSONObject(index)
                val days = profile.getJSONArray("days")
                FocusProfile(profile.getString("id"), profile.getString("name"),
                    FocusSchedule((0 until days.length()).map { days.getInt(it) }.toSet(), profile.getInt("start"), profile.getInt("end")))
            },
            rules = rules.keys().asSequence().associateWith { packageName ->
                val rule = rules.getJSONObject(packageName)
                AppRule(rule.getInt("minutes"), rule.getBoolean("remember"), PromptTone.valueOf(rule.getString("tone")),
                    if (rule.isNull("profile")) null else rule.getString("profile"), rule.getInt("extensions"), rule.getInt("cooldown"))
            },
            budgets = (0 until budgets.length()).map { index ->
                val budget = budgets.getJSONObject(index)
                val packages = budget.getJSONArray("packages")
                SharedBudget(budget.getString("id"), budget.getString("name"), budget.getInt("minutes"),
                    (0 until packages.length()).map { packages.getString(it) }.toSet())
            },
            weeklyGoalMinutes = root.optInt("goal", 0)
        ).validated()
    }
}