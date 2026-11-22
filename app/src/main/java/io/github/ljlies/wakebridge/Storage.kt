package io.github.ljlies.wakebridge

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * Preferences kept in device-protected storage, readable after a restart before the phone is
 * first unlocked: what the phone alarm needs to ring then.
 */
object Storage {
    fun deviceProtected(context: Context, name: String): SharedPreferences {
        val protected = context.createDeviceProtectedStorageContext()
        // Earlier versions kept these in ordinary storage: move them over once
        if (unlocked(context)) {
            val flags = protected.getSharedPreferences("storage", Context.MODE_PRIVATE)
            if (!flags.getBoolean("moved_$name", false)) {
                protected.moveSharedPreferencesFrom(context, name)
                flags.edit().putBoolean("moved_$name", true).apply()
            }
        }
        return protected.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /** False after a restart until the phone is first unlocked: only device-protected storage works. */
    fun unlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java).isUserUnlocked
}
