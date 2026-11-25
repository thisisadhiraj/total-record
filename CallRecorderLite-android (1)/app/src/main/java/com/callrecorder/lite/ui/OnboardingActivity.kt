package com.callrecorder.lite.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.callrecorder.lite.R
import com.callrecorder.lite.core.SetupHealthChecker
import com.callrecorder.lite.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClicks()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    @SuppressLint("BatteryLife")
    private fun setupClicks() {
        binding.btnStep1Grant.setOnClickListener {
            permissionLauncher.launch(SetupHealthChecker.requiredRuntimePermissions())
        }

        binding.btnStep2Enable.setOnClickListener {
            openSafeSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnStep3AppInfo.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            openSafeSettings(intent)
        }

        binding.btnStep3Accessibility.setOnClickListener {
            openSafeSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnStep4Battery.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            if (!openSafeSettings(intent)) {
                openSafeSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        binding.btnContinue.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun refreshStatuses() {
        val step1Ok = SetupHealthChecker.hasBasicPermissions(this)
        val step2Ok = SetupHealthChecker.isNotificationListenerEnabled(this)
        val step3Ok = SetupHealthChecker.isAccessibilityServiceEnabled(this)
        val step4Ok = SetupHealthChecker.isIgnoringBatteryOptimizations(this)

        applyStatusBadge(binding.tvStep1Status, step1Ok)
        applyStatusBadge(binding.tvStep2Status, step2Ok)
        applyStatusBadge(binding.tvStep3Status, step3Ok)
        applyStatusBadge(binding.tvStep4Status, step4Ok)

        binding.btnStep1Grant.isEnabled = !step1Ok
        binding.btnStep4Battery.isEnabled = !step4Ok
    }

    private fun applyStatusBadge(view: TextView, enabled: Boolean) {
        if (enabled) {
            view.text = getString(R.string.status_enabled)
            view.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
        } else {
            view.text = getString(R.string.status_required)
            view.setTextColor(ContextCompat.getColor(this, R.color.status_warn_text))
        }
    }

    private fun openSafeSettings(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
