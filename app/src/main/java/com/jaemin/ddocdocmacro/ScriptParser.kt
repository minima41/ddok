package com.jaemin.ddocdocmacro

object ScriptParser {
    data class ParsedScript(val steps: List<MacroStep>, val errors: List<String>)

    fun parse(script: String): ParsedScript {
        val steps = mutableListOf<MacroStep>()
        val errors = mutableListOf<String>()

        script.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed

            try {
                parseLine(line)?.let(steps::add)
            } catch (e: IllegalArgumentException) {
                errors += "${lineNumber}행: ${e.message ?: "잘못된 명령"}"
            }
        }
        return ParsedScript(steps, errors)
    }

    private fun parseLine(line: String): MacroStep? {
        val textParts = line.split("|", limit = 2)
        val commandPart = textParts[0].trim()
        val payload = textParts.getOrNull(1)?.trim().orEmpty()
        val tokens = commandPart.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        return when (val command = tokens[0].uppercase()) {
            "WAIT" -> {
                require(tokens.size == 2) { "WAIT 형식: WAIT 1000" }
                MacroStep.Wait(tokens[1].positiveLong("대기시간"))
            }
            "WAIT_UNTIL" -> {
                require(tokens.size == 2) { "WAIT_UNTIL 형식: WAIT_UNTIL 07:00:00.000" }
                require(TimeUtils.isValidTime(tokens[1])) { "시간 형식은 HH:mm:ss 또는 HH:mm:ss.SSS" }
                MacroStep.WaitUntil(tokens[1])
            }
            "TAP_TEXT", "TAP_TEXT_EXACT" -> {
                require(tokens.size in 1..2) { "$command 형식: $command 5000 | 버튼 문구" }
                val timeout = tokens.getOrNull(1)?.positiveLong("탐색 제한시간") ?: 5_000L
                require(payload.isNotBlank()) { "$command 뒤에 | 버튼 문구가 필요합니다" }
                MacroStep.TapText(payload, timeout, command == "TAP_TEXT_EXACT")
            }
            "TRY_TEXT", "TRY_TEXT_EXACT" -> {
                require(tokens.size in 1..2) { "$command 형식: $command 1500 | 버튼 문구" }
                val timeout = tokens.getOrNull(1)?.positiveLong("탐색 제한시간") ?: 1_500L
                require(payload.isNotBlank()) { "$command 뒤에 | 버튼 문구가 필요합니다" }
                MacroStep.TryText(payload, timeout, command == "TRY_TEXT_EXACT")
            }
            "RETRY_TEXT" -> {
                require(tokens.size in 1..3) { "RETRY_TEXT 형식: RETRY_TEXT 10000 80 | 예약하기" }
                val timeout = tokens.getOrNull(1)?.positiveLong("탐색 제한시간") ?: 10_000L
                val interval = tokens.getOrNull(2)?.positiveLong("재시도 간격") ?: 100L
                require(payload.isNotBlank()) { "RETRY_TEXT 뒤에 | 버튼 문구가 필요합니다" }
                MacroStep.RetryText(payload, timeout, interval.coerceAtLeast(30L))
            }
            "BOOK_APPOINTMENT" -> {
                require(tokens.size in 1..4) {
                    "BOOK_APPOINTMENT 형식: BOOK_APPOINTMENT 16000 30 1800 | 09:10,09:20,09:30"
                }
                val timeout = tokens.getOrNull(1)?.positiveLong("전체 제한시간") ?: 16_000L
                val poll = tokens.getOrNull(2)?.positiveLong("탐색 간격") ?: 30L
                val resultWait = tokens.getOrNull(3)?.positiveLong("최종 결과 대기시간") ?: 1_800L
                val priorities = payload.split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                require(priorities.isNotEmpty()) { "예약 희망 시간을 쉼표로 입력하세요" }
                require(priorities.all { Regex("^(?:0?[1-9]|1[0-2]):[0-5]\\d$").matches(it) }) {
                    "시간은 09:10,09:20처럼 입력하세요"
                }
                MacroStep.BookAppointment(
                    priorities = priorities,
                    timeoutMillis = timeout,
                    pollIntervalMillis = poll.coerceAtLeast(20L),
                    resultWaitMillis = resultWait.coerceAtLeast(500L)
                )
            }
            "TAP" -> {
                require(tokens.size == 3) { "TAP 형식: TAP 540 1800" }
                MacroStep.Tap(tokens[1].number("x"), tokens[2].number("y"))
            }
            "TAP_PCT" -> {
                require(tokens.size == 3) { "TAP_PCT 형식: TAP_PCT 50 90" }
                val x = tokens[1].number("x 비율")
                val y = tokens[2].number("y 비율")
                require(x in 0f..100f && y in 0f..100f) { "비율은 0~100 사이여야 합니다" }
                MacroStep.TapPercent(x, y)
            }
            "SWIPE" -> {
                require(tokens.size == 6) { "SWIPE 형식: SWIPE 500 1700 500 700 350" }
                MacroStep.Swipe(
                    tokens[1].number("시작 x"),
                    tokens[2].number("시작 y"),
                    tokens[3].number("끝 x"),
                    tokens[4].number("끝 y"),
                    tokens[5].positiveLong("스와이프 시간")
                )
            }
            "BACK" -> {
                require(tokens.size == 1) { "BACK 뒤에는 값이 없습니다" }
                MacroStep.Back
            }
            "HOME" -> {
                require(tokens.size == 1) { "HOME 뒤에는 값이 없습니다" }
                MacroStep.Home
            }
            else -> throw IllegalArgumentException("지원하지 않는 명령: $command")
        }
    }

    private fun String.positiveLong(name: String): Long {
        val value = toLongOrNull() ?: throw IllegalArgumentException("$name 숫자가 잘못되었습니다: $this")
        require(value >= 0L) { "${name}은 0 이상이어야 합니다" }
        return value
    }

    private fun String.number(name: String): Float =
        toFloatOrNull() ?: throw IllegalArgumentException("$name 숫자가 잘못되었습니다: $this")
}
