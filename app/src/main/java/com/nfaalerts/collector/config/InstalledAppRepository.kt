package com.nfaalerts.collector.config

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val flags: Int,
    val icon: ImageBitmap? = null,
) {
    val isSystem: Boolean = InstalledAppClassifier.isSystem(flags)
}

object InstalledAppClassifier {
    fun isSystem(flags: Int): Boolean =
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun visibleApps(
        apps: List<InstalledApp>,
        showSystemApps: Boolean,
        selectedPackages: Set<String>,
        query: String = "",
    ): List<InstalledApp> =
        apps
            .filter { showSystemApps || !isSystem(it.flags) || it.packageName in selectedPackages }
            .filter {
                query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, InstalledApp::label)
                    .thenBy(InstalledApp::packageName),
            )
}

class InstalledAppRepository(
    private val packageManager: PackageManager,
) {
    suspend fun installedApps(): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            packageManager
                .installedApplicationsCompat()
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = packageManager.getApplicationLabel(info).toString(),
                        flags = info.flags,
                        icon =
                            runCatching {
                                info.loadIcon(packageManager).toBitmap(width = 48, height = 48).asImageBitmap()
                            }.getOrNull(),
                    )
                }
        }
}

@Suppress("DEPRECATION")
internal fun PackageManager.installedApplicationsCompat(): List<ApplicationInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        getInstalledApplications(0)
    }

@Suppress("DEPRECATION")
internal fun PackageManager.applicationInfoCompat(packageName: String): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        getApplicationInfo(packageName, 0)
    }
