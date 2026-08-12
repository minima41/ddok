package com.jaemin.ddocdocmacro

import android.content.Context
import androidx.core.content.edit
import java.util.Locale

object Prefs {
    const val TARGET_PACKAGE = "com.bbros.sayup"

    private const val NAME = "ddocdoc_macro_prefs"
    private const val KEY_TIME = "time"
    private const val KEY_DAYS = "days"
    private const val KEY_SCRIPT = "script"
    private const val KEY_PATIENT = "patient"
    private const val KEY_TIME_PRIORITIES = "time_priorities"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PENDING = "pending"
    private const val KEY_TEST_MODE = "test_mode"
    private const val KEY_RECORDING = "recording"
    private const val KEY_RECORDED_SCRIPT = "recorded_script"
    private const val KEY_RECORD_COUNT = "record_count"
    private const val KEY_LAST_RUN = "last_run"
    private const val KEY_LAST_RESULT = "last_result"

    const val DEFAULT_TIME = "06:59:45.000"
    const val DEFAULT_DAYS_MASK = 0b1111111
    const val DEFAULT_PATIENT = "이도아"
    const val NO_TIME = "사용 안 함"

    val DEFAULT_TIME_PRIORITIES = listOf("09:10", "09:20", "09:30")

    val BOOKING_TIME_OPTIONS: List<String> = buildList {
        add(NO_TIME)
        for (hour in 9..12) {
            for (minute in 0..50 step 10) {
                add(String.format(Locale.US, "%02d:%02d", hour, minute))
            }
        }
    }

    val CHAEEUM_PRESET_SCRIPT = """
        # 채움소아청소년과 / 예약 대상과 시간 우선순위는 앱 화면에서 선택합니다.
        # 똑닥이 마지막으로 열었던 채움소아청소년과 화면을 복원한다는 기준입니다.
        WAIT 700
        TAP_TEXT_EXACT 5000 | 시간예약
        WAIT 180
        TAP_TEXT_EXACT 5000 | {{PATIENT}}
        WAIT 180
        TAP_TEXT 4000 | [1진료실] 김민채 원장님

        # 진료실 선택 직후 공지가 뜨는 날에만 확인하고, 없으면 그대로 넘어갑니다.
        TRY_TEXT_EXACT 1200 | 확인
        WAIT 120
        TAP_TEXT_EXACT 3000 | 일반진료

        # 일반진료 선택 화면에서 정각까지 대기한 뒤 시간표를 새로 불러옵니다.
        WAIT_UNTIL 07:00:00.000
        RETRY_TEXT 2500 30 | 다음

        # 앱 화면에서 고른 1~5순위 시간을 차례대로 시도합니다.
        BOOK_APPOINTMENT 18000 30 1800 | {{TIME_PRIORITIES}}
    """.trimIndent()

    val DEFAULT_SCRIPT = CHAEEUM_PRESET_SCRIPT

    private val patientLineRegex = Regex(
        pattern = "(?m)^(\\s*TAP_TEXT_EXACT(?:\\s+\\d+)?\\s*\\|\\s*)(이도아|이도연)\\s*$"
    )
    private val bookingLineRegex = Regex(
        pattern = "(?m)^(\\s*BOOK_APPOINTMENT(?:\\s+\\d+){0,3}\\s*\\|\\s*).*$"
    )

    private fun sp(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun time(context: Context): String = sp(context).getString(KEY_TIME, DEFAULT_TIME) ?: DEFAULT_TIME
    fun setTime(context: Context, value: String) = sp(context).edit { putString(KEY_TIME, value) }

    fun daysMask(context: Context): Int = sp(context).getInt(KEY_DAYS, DEFAULT_DAYS_MASK)
    fun setDaysMask(context: Context, value: Int) = sp(context).edit { putInt(KEY_DAYS, value) }

    fun script(context: Context): String = sp(context).getString(KEY_SCRIPT, DEFAULT_SCRIPT) ?: DEFAULT_SCRIPT
    fun setScript(context: Context, value: String) = sp(context).edit { putString(KEY_SCRIPT, value) }

    fun patient(context: Context): String =
        sp(context).getString(KEY_PATIENT, DEFAULT_PATIENT) ?: DEFAULT_PATIENT

    fun setPatient(context: Context, value: String) = sp(context).edit {
        putString(KEY_PATIENT, value)
    }

    fun timePriorities(context: Context): List<String> {
        val raw = sp(context).getString(KEY_TIME_PRIORITIES, null)
        return raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_TIME_PRIORITIES
    }

    fun setTimePriorities(context: Context, values: List<String>) = sp(context).edit {
        putString(
            KEY_TIME_PRIORITIES,
            values.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(",")
        )
    }

    fun renderTemplate(template: String, patient: String, priorities: List<String>): String {
        val cleaned = priorities.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val timeText = cleaned.joinToString(",")

        var rendered = template
            .replace("{{PATIENT}}", patient)
            .replace("{{TIME_PRIORITIES}}", timeText)

        rendered = patientLineRegex.replace(rendered) { match ->
            "${match.groupValues[1]}$patient"
        }
        rendered = bookingLineRegex.replace(rendered) { match ->
            "${match.groupValues[1]}$timeText"
        }
        return rendered
    }

    fun renderedScript(context: Context): String =
        renderTemplate(script(context), patient(context), timePriorities(context))

    fun enabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) = sp(context).edit { putBoolean(KEY_ENABLED, value) }

    fun pending(context: Context): Boolean = sp(context).getBoolean(KEY_PENDING, false)
    fun setPending(context: Context, value: Boolean, testMode: Boolean = false) = sp(context).edit {
        putBoolean(KEY_PENDING, value)
        putBoolean(KEY_TEST_MODE, testMode)
    }

    fun testMode(context: Context): Boolean = sp(context).getBoolean(KEY_TEST_MODE, false)

    fun recording(context: Context): Boolean = sp(context).getBoolean(KEY_RECORDING, false)
    fun setRecording(context: Context, value: Boolean) = sp(context).edit { putBoolean(KEY_RECORDING, value) }

    fun recordedScript(context: Context): String = sp(context).getString(KEY_RECORDED_SCRIPT, "") ?: ""
    fun clearRecording(context: Context) = sp(context).edit {
        putString(KEY_RECORDED_SCRIPT, "")
        putInt(KEY_RECORD_COUNT, 0)
        putBoolean(KEY_RECORDING, true)
    }

    fun appendRecordedStep(context: Context, step: String) {
        val current = recordedScript(context)
        val count = recordCount(context)
        val next = buildString {
            if (current.isBlank()) {
                appendLine("# 녹화된 똑닥 클릭 경로")
                appendLine("WAIT 1000")
            } else {
                append(current.trimEnd())
                appendLine()
            }
            if (count > 0) appendLine("WAIT 250")
            appendLine(step)
        }
        sp(context).edit {
            putString(KEY_RECORDED_SCRIPT, next.trimEnd())
            putInt(KEY_RECORD_COUNT, count + 1)
        }
    }

    fun recordCount(context: Context): Int = sp(context).getInt(KEY_RECORD_COUNT, 0)

    fun markRunResult(context: Context, result: String) = sp(context).edit {
        putLong(KEY_LAST_RUN, System.currentTimeMillis())
        putString(KEY_LAST_RESULT, result)
    }

    fun lastRun(context: Context): Long = sp(context).getLong(KEY_LAST_RUN, 0L)
    fun lastResult(context: Context): String = sp(context).getString(KEY_LAST_RESULT, "실행 기록 없음") ?: "실행 기록 없음"
}
