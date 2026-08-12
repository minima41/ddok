package com.jaemin.ddocdocmacro

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class AlarmLaunchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        Prefs.setPending(this, true, testMode = false)
        AlarmScheduler.scheduleNext(this)

        val launch = packageManager.getLaunchIntentForPackage(Prefs.TARGET_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        if (launch == null) {
            Prefs.setPending(this, false)
            Prefs.markRunResult(this, "똑닥 앱을 찾지 못했습니다")
            NotificationHelper.show(this, "똑닥 매크로 실패", "똑닥 앱이 설치되어 있는지 확인하세요.")
            finish()
            return
        }

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, null)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { startActivity(launch) }
                .onFailure {
                    Prefs.setPending(this, false)
                    Prefs.markRunResult(this, "똑닥 실행 실패: ${it.message}")
                    NotificationHelper.show(this, "똑닥 매크로 실패", "똑닥 앱 실행에 실패했습니다.")
                }
            finish()
        }, 180L)
    }
}
