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

    /**
     * 오늘 목표 시각이 60초 이내로 이미 지났다면 즉시 실행하고,
     * 그보다 오래 지났다면 다음 날 목표 시각으로 계산합니다.
     */
    fun nextTargetMillis(timeText: String, nowMillis: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        val targetTime = parseLocalTime(timeText)
        var target = LocalDateTime.of(LocalDate.now(zone), targetTime)
        if (target.isBefore(now.minusSeconds(60))) target = target.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }
}
