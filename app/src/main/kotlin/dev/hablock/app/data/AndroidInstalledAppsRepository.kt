package dev.hablock.app.data

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import dev.hablock.app.domain.model.InstalledApp
import dev.hablock.app.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidInstalledAppsRepository(context: Context) : InstalledAppsRepository {

    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    override suspend fun launcherApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val homes = queryPackages(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        runCatching { packageManager.queryIntentActivities(launcherIntent, 0) }
            .getOrDefault(emptyList())
            .asSequence()
            .map { resolveInfo ->
                InstalledApp(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(packageManager).toString(),
                )
            }
            .filter { it.packageName != appContext.packageName && it.packageName !in homes }
            .filter { it.packageName !in essentials }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Apps that must keep working while a block is on: the phone, messages, settings, camera, clock,
     * keyboard and health plumbing. Everything else on the launcher is blockable — a "block all"
     * block must never be able to take away the ability to call for help or undo it.
     */
    private val essentials: Set<String> by lazy {
        val packages = mutableSetOf(
            appContext.packageName,
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.apps.healthdata",
            "com.google.android.healthconnect.controller",
            "com.android.healthconnect",
            "com.google.android.contacts",
            "com.android.contacts",
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.google.android.GoogleCamera",
            "com.android.camera2",
        )
        runCatching {
            appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()?.let(packages::add)
        runCatching {
            android.provider.Telephony.Sms.getDefaultSmsPackage(appContext)
        }.getOrNull()?.let(packages::add)
        runCatching {
            appContext.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapNotNull { it.packageName }
                ?.let(packages::addAll)
        }
        packages
    }

    override suspend fun homePackages(): Set<String> = withContext(Dispatchers.IO) {
        queryPackages(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }

    @Suppress("DEPRECATION")
    private fun queryPackages(intent: Intent): Set<String> =
        runCatching { appContext.packageManager.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
}
