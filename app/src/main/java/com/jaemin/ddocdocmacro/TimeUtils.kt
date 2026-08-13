package com.jaemin.ddocdocmacro

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

object TimeUtils {
    private val formatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true)
        .optionalEnd()
        .toFormatter()

    fun parseLocalTime(text: String): LocalTime = LocalTime.parse(text, formatter)

    fun isValidTime(text: String): Boolean = try {
        parseLocalTime(text)
        true
    } catch (_: DateTimeParseException) {
        false
    }

    fun nextTargetMillis(timeText: String, nowMillis: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val targetTime = parseLocalTime(timeText)
        val target = LocalDateTime.of(LocalDate.now(zone), targetTime)
        return target.atZone(zone).toInstant().toEpochMilli()
    }
}
