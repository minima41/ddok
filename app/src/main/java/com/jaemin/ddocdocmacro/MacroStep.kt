package com.jaemin.ddocdocmacro

sealed interface MacroStep {
    data class Wait(val millis: Long) : MacroStep
    data class WaitUntil(val timeText: String) : MacroStep
    data class TapText(val text: String, val timeoutMillis: Long, val exact: Boolean) : MacroStep
    data class TryText(val text: String, val timeoutMillis: Long, val exact: Boolean) : MacroStep
    data class RetryText(val text: String, val timeoutMillis: Long, val intervalMillis: Long) : MacroStep
    data class BookAppointment(
        val priorities: List<String>,
        val timeoutMillis: Long,
        val pollIntervalMillis: Long,
        val resultWaitMillis: Long
    ) : MacroStep
    data class Tap(val x: Float, val y: Float) : MacroStep
    data class TapPercent(val xPercent: Float, val yPercent: Float) : MacroStep
    data class Swipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMillis: Long
    ) : MacroStep
    data object Back : MacroStep
    data object Home : MacroStep
}
