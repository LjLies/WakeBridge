package io.github.ljlies.wakebridge

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Day, Night and Settings panes. Also opened by long-pressing the Quick Settings tile. */
class MainActivity : AppCompatActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        if (savedInstanceState == null) {
            nav.selectedItemId = tabFor(intent)
        } else if (intent.action == ACTION_TILE_PREFERENCES) {
            nav.selectedItemId = tabFor(intent)
        }
        askForNotifications()
        if (savedInstanceState == null) {
            noticeForceStop()
            // A force stop cancels all of the app's timers: opening the app puts them back
            Scheduler.restore(this)
        }
    }

    /** Says so when Android (or the user) force-stopped the app since it last ran. */
    private fun noticeForceStop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = getSharedPreferences("exits", MODE_PRIVATE)
        val seen = prefs.getLong("seen", 0)
        val exits = getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 5)
        val stopped = exits.filter { it.timestamp > seen && it.reason == ApplicationExitInfo.REASON_USER_REQUESTED }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("seen", maxOf(seen, it)).apply() }
        // The first time, there's no telling which exits are new
        if (seen == 0L || stopped.isEmpty()) return
        val at = java.time.Instant.ofEpochMilli(stopped.maxOf { it.timestamp })
        EventLog.add(this, getString(R.string.log_force_stopped, Format.dateTime(this, at)))
        Toast.makeText(this, R.string.toast_force_stopped, Toast.LENGTH_LONG).show()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // The tile's long press, or the launcher: show the tab that applies now
        findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = tabFor(intent)
    }

    private fun show(id: Int) {
        val tag = "tab$id"
        val fragments = supportFragmentManager
        val transaction = fragments.beginTransaction()
        fragments.fragments.forEach { if (it.tag != tag) transaction.hide(it) }
        val existing = fragments.findFragmentByTag(tag)
        if (existing != null) {
            transaction.show(existing)
        } else {
            val fragment: Fragment = when (id) {
                R.id.tab_night -> PaneFragment.create(night = true)
                R.id.tab_settings -> SettingsFragment()
                else -> PaneFragment.create(night = false)
            }
            transaction.add(R.id.content, fragment, tag)
        }
        transaction.commit()
    }

    /** The phone backup alarm rings through a notification (Android 13+ asks first). */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val settings = Settings(this)
        if (!settings.phoneBackup && !settings.phoneOnly) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("asked", MODE_PRIVATE)
        if (prefs.getBoolean("notifications", false)) return
        prefs.edit().putBoolean("notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Night sleep while a night is running or armed for tonight, else Day naps. */
    private fun tabFor(intent: android.content.Intent?): Int = when {
        intent?.getBooleanExtra(EXTRA_SETTINGS, false) == true -> R.id.tab_settings
        Session.current(this)?.kind == Session.Kind.NIGHT || Settings(this).nightTonight -> R.id.tab_night
        else -> R.id.tab_day
    }

    companion object {
        const val EXTRA_SETTINGS = "settings"
        private const val ACTION_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"
    }
}
