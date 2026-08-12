package com.jaemin.ddocdocmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.resume

class AutomationAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutomationAccessibilityService? = null
            private set

        private val failureMarkers = listOf(
            "마감", "이미 예약", "예약할 수 없", "예약이 불가", "다른 분이 먼저",
            "다른 사용자가", "선택한 시간이", "다시 선택", "접수가 종료", "예약 종료"
        )
        private val successMarkers = listOf(
            "예약이 완료", "예약 완료", "접수가 완료", "예약 당일", "푸시 알림"
        )

        fun requestManualRun(context: Context, testMode: Boolean) {
            Prefs.setPending(context, true, testMode)
            val launch = context.packageManager.getLaunchIntentForPackage(Prefs.TARGET_PACKAGE)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (launch == null) {
                Prefs.setPending(context, false)
                Prefs.markRunResult(context, "똑닥 앱을 찾지 못했습니다")
                NotificationHelper.show(context, "똑닥 매크로 실패", "똑닥 앱이 설치되어 있는지 확인하세요.")
                return
            }
            runCatching { context.startActivity(launch) }
            instance?.startPendingIfAny()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null
    private var running = false
    private var overlay: TextView? = null
    private var lastRecordedSignature = ""
    private var lastRecordedAt = 0L
    private var runSummary = "실행 완료"

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        NotificationHelper.ensureChannel(this)
        if (Prefs.recording(this)) showRecordingOverlay()
        if (Prefs.pending(this)) startPendingIfAny()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != Prefs.TARGET_PACKAGE) return

        if (Prefs.recording(this)) {
            showRecordingOverlay()
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) recordClick(event)
        } else {
            removeRecordingOverlay()
        }

        if (Prefs.pending(this) && !running) startPendingIfAny()
    }

    override fun onInterrupt() {
        runJob?.cancel()
        running = false
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runJob?.cancel()
        removeRecordingOverlay()
        super.onDestroy()
    }

    fun startPendingIfAny() {
        if (running || !Prefs.pending(this)) return
        running = true
        runJob = scope.launch {
            delay(150L)
            val result = runCatching { executeSavedScript() }
            Prefs.setPending(this@AutomationAccessibilityService, false)
            running = false

            result.onSuccess {
                Prefs.markRunResult(this@AutomationAccessibilityService, runSummary)
                NotificationHelper.show(this@AutomationAccessibilityService, "똑닥 매크로 완료", runSummary)
            }.onFailure { e ->
                val msg = e.message ?: e.javaClass.simpleName
                Prefs.markRunResult(this@AutomationAccessibilityService, "실패: $msg")
                NotificationHelper.show(this@AutomationAccessibilityService, "똑닥 매크로 실패", msg)
            }
        }
    }

    private suspend fun executeSavedScript() {
        waitForTargetWindow()
        val parsed = ScriptParser.parse(Prefs.renderedScript(this))
        require(parsed.errors.isEmpty()) { parsed.errors.joinToString("\n") }
        require(parsed.steps.isNotEmpty()) { "실행할 명령이 없습니다." }
        val testMode = Prefs.testMode(this)

        parsed.steps.forEachIndexed { index, step ->
            when (step) {
                is MacroStep.Wait -> delay(step.millis)
                is MacroStep.WaitUntil -> waitUntil(step.timeText, testMode)
                is MacroStep.TapText -> check(waitAndTapText(step.text, step.timeoutMillis, 80L, step.exact)) {
                    "${index + 1}단계 '${step.text}' 버튼을 찾지 못했습니다."
                }
                is MacroStep.TryText -> waitAndTapText(step.text, step.timeoutMillis, 60L, step.exact)
                is MacroStep.RetryText -> check(waitAndTapText(step.text, step.timeoutMillis, step.intervalMillis, false)) {
                    "${index + 1}단계 '${step.text}' 버튼이 활성화되지 않았습니다."
                }
                is MacroStep.BookAppointment -> {
                    val chosen = bookAppointment(step, testMode)
                    runSummary = if (testMode) {
                        "테스트 완료: $chosen 선택 후 최종 예약 직전까지 도달"
                    } else {
                        "$chosen 예약 시도 완료"
                    }
                }
                is MacroStep.Tap -> check(dispatchTap(step.x, step.y)) { "좌표 탭 실패" }
                is MacroStep.TapPercent -> {
                    val m = resources.displayMetrics
                    check(dispatchTap(m.widthPixels * step.xPercent / 100f, m.heightPixels * step.yPercent / 100f)) { "비율 좌표 탭 실패" }
                }
                is MacroStep.Swipe -> check(dispatchSwipe(step.startX, step.startY, step.endX, step.endY, step.durationMillis)) { "스와이프 실패" }
                MacroStep.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
                MacroStep.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            }
            if (step !is MacroStep.Wait && step !is MacroStep.WaitUntil) delay(70L)
        }
    }

    private suspend fun waitForTargetWindow(timeoutMillis: Long = 10_000L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() <= deadline) {
            if (rootInActiveWindow?.packageName?.toString() == Prefs.TARGET_PACKAGE) return
            delay(50L)
        }
        error("똑닥 화면이 열리지 않았습니다.")
    }

    private suspend fun waitUntil(timeText: String, testMode: Boolean) {
        if (testMode) return
        val target = TimeUtils.nextTargetMillis(timeText)
        while (true) {
            val remaining = target - System.currentTimeMillis()
            if (remaining <= 0L) return
            when {
                remaining > 1000L -> delay(remaining - 500L)
                remaining > 80L -> delay(remaining - 30L)
                remaining > 8L -> delay(remaining - 3L)
                else -> yield()
            }
        }
    }

    private suspend fun waitAndTapText(text: String, timeoutMillis: Long, intervalMillis: Long, exact: Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            if (tapNodeByText(text, exact)) return true
            delay(intervalMillis.coerceAtLeast(20L))
        } while (SystemClock.uptimeMillis() <= deadline)
        return false
    }

    private suspend fun bookAppointment(step: MacroStep.BookAppointment, testMode: Boolean): String {
        val deadline = SystemClock.uptimeMillis() + step.timeoutMillis
        val attempted = linkedSetOf<String>()

        while (SystemClock.uptimeMillis() <= deadline) {
            dismissFailureDialogIfPresent()
            ensureTimeSelectionScreen()

            val candidate = step.priorities.firstOrNull { it !in attempted && hasEnabledTime(it) }
            if (candidate == null) {
                val remaining = step.priorities.filterNot { it in attempted }
                if (remaining.isEmpty()) break
                delay(step.pollIntervalMillis)
                continue
            }

            attempted += candidate
            if (!tapTime(candidate)) continue
            delay(60L)

            if (!waitAndTapText("다음", 1_200L, 25L, exact = true)) {
                recoverToTimeSelection()
                continue
            }

            val finalReady = waitForText("동의하고 예약하기", 1_500L, exact = true)
            if (!finalReady) {
                if (screenHasAny(failureMarkers)) {
                    dismissFailureDialogIfPresent()
                    recoverToTimeSelection()
                    continue
                }
                recoverToTimeSelection()
                continue
            }

            if (testMode) return candidate

            if (!tapNodeByText("동의하고 예약하기", exact = true)) {
                recoverToTimeSelection()
                continue
            }

            when (waitForBookingOutcome(step.resultWaitMillis)) {
                BookingOutcome.SUCCESS -> return candidate
                BookingOutcome.FAILURE -> {
                    dismissFailureDialogIfPresent()
                    recoverToTimeSelection()
                }
                BookingOutcome.UNKNOWN -> {
                    if (!screenContainsText("동의하고 예약하기", exact = true) && !screenContainsText("시간 선택")) {
                        return candidate
                    }
                    recoverToTimeSelection()
                }
            }
        }
        error("선택한 시간(${step.priorities.joinToString(", ")})을 모두 시도했지만 예약하지 못했습니다.")
    }

    private enum class BookingOutcome { SUCCESS, FAILURE, UNKNOWN }

    private suspend fun waitForBookingOutcome(timeoutMillis: Long): BookingOutcome {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() <= deadline) {
            if (screenHasAny(successMarkers)) return BookingOutcome.SUCCESS
            if (screenHasAny(failureMarkers)) return BookingOutcome.FAILURE
            delay(40L)
        }
        return BookingOutcome.UNKNOWN
    }

    private suspend fun ensureTimeSelectionScreen() {
        if (screenContainsText("시간 선택")) return
        if (screenContainsText("동의하고 예약하기")) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(100L)
        }
    }

    private suspend fun recoverToTimeSelection() {
        repeat(3) {
            if (screenContainsText("시간 선택")) return
            if (tapNodeByText("확인", exact = true)) delay(100L)
            if (screenContainsText("시간 선택")) return
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(120L)
        }
    }

    private suspend fun dismissFailureDialogIfPresent(): Boolean {
        if (!screenHasAny(failureMarkers)) return false
        tapNodeByText("확인", exact = true)
        delay(100L)
        return true
    }

    private suspend fun waitForText(text: String, timeoutMillis: Long, exact: Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() <= deadline) {
            if (screenContainsText(text, exact)) return true
            delay(30L)
        }
        return false
    }

    private fun screenHasAny(markers: List<String>): Boolean = markers.any { screenContainsText(it, false) }

    private fun screenContainsText(text: String, exact: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        return findMatchingNode(root, text, exact) { true } != null
    }

    private fun hasEnabledTime(candidate: String): Boolean {
        val normalized = normalizeClock(candidate) ?: return false
        return findNode { node ->
            val labels = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
            labels.any { normalizeClock(it) == normalized } && clickableAncestor(node)?.let { it.isEnabled && it.isVisibleToUser } == true
        } != null
    }

    private fun tapTime(candidate: String): Boolean {
        val normalized = normalizeClock(candidate) ?: return false
        val node = findNode { n ->
            listOf(n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty()).any { normalizeClock(it) == normalized }
        } ?: return false
        val clickable = clickableAncestor(node) ?: return false
        return clickable.isEnabled && clickable.isVisibleToUser && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun normalizeClock(value: String): String? {
        val m = Regex("(?<!\\d)(\\d{1,2}):([0-5]\\d)(?!\\d)").find(value) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        if (hour !in 0..23) return null
        return "%02d:%s".format(hour, m.groupValues[2])
    }

    private fun tapNodeByText(text: String, exact: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findMatchingNode(root, text, exact) { true } ?: return false
        val clickable = clickableAncestor(node) ?: return false
        return clickable.isEnabled && clickable.isVisibleToUser && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findMatchingNode(root: AccessibilityNodeInfo, text: String, exact: Boolean, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count++ < 2500) {
            val node = queue.removeFirst()
            val a = node.text?.toString().orEmpty()
            val b = node.contentDescription?.toString().orEmpty()
            val match = if (exact) a == text || b == text else a.contains(text, true) || b.contains(text, true)
            if (match && predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count++ < 2500) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun clickableAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        repeat(8) {
            val n = current ?: return null
            if (n.isClickable) return n
            current = n.parent
        }
        return null
    }

    private suspend fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchPath(path, 1L)
    }

    private suspend fun dispatchSwipe(sx: Float, sy: Float, ex: Float, ey: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        return dispatchPath(path, duration.coerceAtLeast(1L))
    }

    private suspend fun dispatchPath(path: Path, duration: Long): Boolean = suspendCancellableCoroutine { continuation ->
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
        }, null)
        if (!accepted && continuation.isActive) continuation.resume(false)
    }

    private fun recordClick(event: AccessibilityEvent) {
        val source = event.source ?: return
        val bounds = Rect().also(source::getBoundsInScreen)
        val raw = source.text?.toString()?.takeIf { it.isNotBlank() } ?: source.contentDescription?.toString().orEmpty()
        val clean = raw.replace(Regex("\\s+"), " ").replace("|", " ").trim()
        val signature = "$clean:${bounds.centerX()}:${bounds.centerY()}"
        val now = SystemClock.uptimeMillis()
        if (signature == lastRecordedSignature && now - lastRecordedAt < 350L) return
        lastRecordedSignature = signature
        lastRecordedAt = now
        val step = if (clean.isNotBlank() && clean.length <= 60) "TAP_TEXT 5000 | $clean" else "TAP ${bounds.centerX()} ${bounds.centerY()}"
        Prefs.appendRecordedStep(this, step)
        overlay?.text = "녹화 종료 (${Prefs.recordCount(this)})"
    }

    private fun showRecordingOverlay() {
        if (overlay != null) return
        val bg = GradientDrawable().apply { setColor(Color.rgb(179, 38, 30)); cornerRadius = 28f }
        val view = TextView(this).apply {
            text = "녹화 종료 (${Prefs.recordCount(this@AutomationAccessibilityService)})"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(28, 18, 28, 18)
            background = bg
            setOnClickListener { stopRecordingAndOpenApp() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 22; y = 90 }
        runCatching { getSystemService(WindowManager::class.java).addView(view, params); overlay = view }
    }

    private fun removeRecordingOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    private fun stopRecordingAndOpenApp() {
        Prefs.setRecording(this, false)
        removeRecordingOverlay()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("load_recording", true)
        })
    }
}
