package com.jaemin.ddocdocmacro

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jaemin.ddocdocmacro.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var loadingQuickSettings = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateStatus() }

    private val dayBoxes: List<CheckBox>
        get() = listOf(binding.dayMon,binding.dayTue,binding.dayWed,binding.dayThu,binding.dayFri,binding.daySat,binding.daySun)

    private val prioritySpinners: List<Spinner>
        get() = listOf(binding.priority1Spinner,binding.priority2Spinner,binding.priority3Spinner,binding.priority4Spinner,binding.priority5Spinner)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NotificationHelper.ensureChannel(this)
        setupQuickSettings()
        loadPreferences()
        wireButtons()
        requestNotificationPermissionIfNeeded()
        handleRecordingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleRecordingIntent(intent) }

    override fun onResume() {
        super.onResume()
        if (Prefs.enabled(this) && AlarmScheduler.canScheduleExact(this)) AlarmScheduler.scheduleNext(this)
        updateStatus()
    }

    private fun setupQuickSettings() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Prefs.BOOKING_TIME_OPTIONS).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (!loadingQuickSettings) updateQuickSummary() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        prioritySpinners.forEach { spinner -> spinner.adapter = adapter; spinner.onItemSelectedListener = listener }
        binding.patientGroup.setOnCheckedChangeListener { _, _ -> if (!loadingQuickSettings) updateQuickSummary() }
    }

    private fun wireButtons() {
        binding.accessibilityButton.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.exactAlarmButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:$packageName") })
            else toast("이 기기에서는 별도 권한이 필요하지 않습니다.")
        }
        binding.batteryButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") })
            toast("배터리 메뉴에서 '제한 없음'으로 설정하세요.")
        }
        binding.openDdocdocButton.setOnClickListener { openDdocdoc() }
        binding.recordButton.setOnClickListener {
            if (!requireAccessibility()) return@setOnClickListener
            if (!isTargetInstalled()) { toast("똑닥 앱이 설치되어 있지 않습니다."); return@setOnClickListener }
            Prefs.clearRecording(this); toast("똑닥에서 예약 직전까지 직접 누른 뒤 화면 위 '녹화 종료'를 누르세요."); openDdocdoc()
        }
        binding.loadRecordingButton.setOnClickListener { loadRecordingIntoEditor() }
        binding.chaeeumPresetButton.setOnClickListener {
            binding.scriptEdit.setText(Prefs.CHAEEUM_PRESET_SCRIPT); binding.scriptEdit.setSelection(binding.scriptEdit.text.length); updateQuickSummary(); toast("선택한 아이와 시간 우선순위가 자동 반영되는 채움소아과 경로를 넣었습니다.")
        }
        binding.testButton.setOnClickListener {
            if (!saveAndValidate()) return@setOnClickListener
            if (!requireAccessibility()) return@setOnClickListener
            AutomationAccessibilityService.requestManualRun(this, testMode = true)
            toast("테스트 실행: 정각 대기는 건너뛰고 최종 예약 버튼 앞에서 멈춥니다.")
        }
        binding.scheduleButton.setOnClickListener {
            if (!saveAndValidate()) return@setOnClickListener
            if (!requireAccessibility()) return@setOnClickListener
            Prefs.setEnabled(this, true)
            if (!AlarmScheduler.canScheduleExact(this)) {
                Prefs.setEnabled(this, false)
                toast("정확한 알람 권한을 켠 뒤 다시 ON 해주세요.")
                binding.exactAlarmButton.performClick()
                updateStatus()
                return@setOnClickListener
            }
            val next = AlarmScheduler.scheduleNext(this)
            if (next == null) {
                Prefs.setEnabled(this, false)
                toast("실행 요일과 시각을 확인하세요.")
            } else {
                toast("자동 예약 ON · 다음 실행: ${AlarmScheduler.format(next)}")
            }
            updateStatus()
        }
        binding.cancelButton.setOnClickListener {
            Prefs.setEnabled(this, false); Prefs.setPending(this, false); Prefs.setRecording(this, false); AlarmScheduler.cancel(this); toast("자동 예약 OFF · 실행을 중지했습니다."); updateStatus()
        }
    }

    private fun loadPreferences() {
        loadingQuickSettings = true
        binding.timeEdit.setText(Prefs.time(this)); binding.scriptEdit.setText(Prefs.script(this))
        val mask = Prefs.daysMask(this)
        dayBoxes.forEachIndexed { index, checkBox -> checkBox.isChecked = mask and (1 shl index) != 0 }
        when (Prefs.patient(this)) { "이도연" -> binding.patientGroup.check(binding.patientDoyeon.id); else -> binding.patientGroup.check(binding.patientDoa.id) }
        val savedPriorities = Prefs.timePriorities(this)
        prioritySpinners.forEachIndexed { index, spinner ->
            val value = savedPriorities.getOrNull(index) ?: Prefs.NO_TIME
            spinner.setSelection(Prefs.BOOKING_TIME_OPTIONS.indexOf(value).takeIf { it >= 0 } ?: 0, false)
        }
        loadingQuickSettings = false; updateQuickSummary()
    }

    private fun selectedPatient(): String = if (binding.patientDoyeon.isChecked) "이도연" else "이도아"
    private fun selectedPriorities(): List<String> = prioritySpinners.mapNotNull { it.selectedItem?.toString() }.filter { it != Prefs.NO_TIME }.distinct()

    private fun updateQuickSummary() {
        val priorities = selectedPriorities(); binding.quickSummaryText.text = "예약 대상: ${selectedPatient()}\n시도 순서: ${priorities.joinToString(" → ").ifBlank { "선택된 시간 없음" }}"
    }

    private fun saveQuickSettings(): Boolean {
        val priorities = selectedPriorities(); if (priorities.isEmpty()) { toast("희망 시간을 하나 이상 선택하세요."); return false }
        Prefs.setPatient(this, selectedPatient()); Prefs.setTimePriorities(this, priorities); return true
    }

    private fun saveAndValidate(): Boolean {
        if (!saveQuickSettings()) return false
        val time = binding.timeEdit.text.toString().trim(); if (!TimeUtils.isValidTime(time)) { toast("시각 형식은 06:59:45 또는 06:59:45.000입니다."); return false }
        val daysMask = dayBoxes.foldIndexed(0) { index, mask, box -> if (box.isChecked) mask or (1 shl index) else mask }
        if (daysMask == 0) { toast("실행 요일을 하나 이상 선택하세요."); return false }
        val scriptTemplate = binding.scriptEdit.text.toString().trim()
        val parsed = ScriptParser.parse(Prefs.renderTemplate(scriptTemplate, selectedPatient(), selectedPriorities()))
        if (parsed.errors.isNotEmpty()) { toast(parsed.errors.take(3).joinToString("\n")); return false }
        if (parsed.steps.isEmpty()) { toast("실행할 클릭 명령이 없습니다."); return false }
        Prefs.setTime(this, time); Prefs.setDaysMask(this, daysMask); Prefs.setScript(this, scriptTemplate); return true
    }

    private fun loadRecordingIntoEditor() {
        val recorded = Prefs.recordedScript(this); if (recorded.isBlank()) { toast("저장된 녹화 경로가 없습니다."); return }
        val finalScript = buildString {
            append(recorded.trim())
            if (!recorded.contains("WAIT_UNTIL")) { appendLine(); appendLine(); appendLine("# 일반진료 선택 후 아래 명령으로 교체하세요."); appendLine("WAIT_UNTIL 07:00:00.000"); appendLine("RETRY_TEXT 2500 30 | 다음"); appendLine("BOOK_APPOINTMENT 18000 30 1800 | {{TIME_PRIORITIES}}") }
        }
        binding.scriptEdit.setText(finalScript); binding.scriptEdit.setSelection(binding.scriptEdit.text.length); toast("녹화 경로를 불러왔습니다. 아이 선택 단계가 문자 클릭인지 확인하세요.")
    }

    private fun handleRecordingIntent(intent: Intent?) { if (intent?.getBooleanExtra("load_recording", false) == true) { intent.removeExtra("load_recording"); loadRecordingIntoEditor() } }
    private fun requireAccessibility(): Boolean { if (isAccessibilityEnabled()) return true; toast("먼저 '똑닥 매크로 접근성 서비스'를 켜세요."); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return false }

    private fun openDdocdoc() {
        val launch = packageManager.getLaunchIntentForPackage(Prefs.TARGET_PACKAGE) ?: run { toast("똑닥 앱을 찾지 못했습니다."); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED); startActivity(launch)
    }
    private fun isTargetInstalled(): Boolean = runCatching { packageManager.getPackageInfo(Prefs.TARGET_PACKAGE, 0) }.isSuccess
    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, AutomationAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
    private fun isBatteryUnrestricted(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun updateStatus() {
        val autoEnabled = Prefs.enabled(this)
        val accessibility = if (isAccessibilityEnabled()) "켜짐" else "꺼짐"
        val exact = if (AlarmScheduler.canScheduleExact(this)) "허용" else "미허용"
        val battery = if (isBatteryUnrestricted()) "제한 없음" else "제한 가능"
        val enabled = if (autoEnabled) "ON" else "OFF"
        val next = if (autoEnabled) AlarmScheduler.computeNextTrigger(this)?.let(AlarmScheduler::format) ?: "계산 불가" else "없음"
        val lastRun = Prefs.lastRun(this).takeIf { it > 0L }?.let { SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA).format(Date(it)) } ?: "없음"
        val target = "${Prefs.patient(this)} / ${Prefs.timePriorities(this).joinToString(" → ")}"

        binding.autoRunStatusText.apply {
            if (autoEnabled) {
                text = "● 자동 예약 ON\n다음 실행: $next"
                setTextColor(Color.parseColor("#1B5E20"))
                setBackgroundColor(Color.parseColor("#E8F5E9"))
            } else {
                text = "○ 자동 예약 OFF\n자동 실행이 중지되어 있습니다"
                setTextColor(Color.parseColor("#B71C1C"))
                setBackgroundColor(Color.parseColor("#FFEBEE"))
            }
        }

        binding.statusText.text = buildString {
            appendLine("자동 예약: $enabled")
            appendLine("접근성: $accessibility  ·  정확한 알람: $exact")
            appendLine("배터리: $battery")
            appendLine("설정: $target")
            appendLine("다음 실행: $next")
            append("최근 결과: $lastRun / ${Prefs.lastResult(this@MainActivity)}")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
