package com.nfaalerts.collector.config

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

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
    private val iconLoader: (ApplicationInfo) -> ImageBitmap? = { info ->
        runCatching {
            info.loadIcon(packageManager).toBitmap(width = 48, height = 48).asImageBitmap()
        }.getOrNull()
    },
    private val maximumCachedIcons: Int = 512,
) {
    private val iconCache = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    internal val cachedIconCount: Int
        get() = synchronized(iconCache) { iconCache.size }

    suspend fun installedApps(): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            packageManager
                .installedApplicationsCompat()
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = packageManager.getApplicationLabel(info).toString(),
                        flags = info.flags,
                        icon = cachedIcon(info),
                    )
                }
        }

    private fun cachedIcon(info: ApplicationInfo): ImageBitmap? {
        synchronized(iconCache) { iconCache[info.packageName]?.let { return it } }
        val loaded = iconLoader(info) ?: return null
        synchronized(iconCache) {
            iconCache[info.packageName] = loaded
            while (iconCache.size > maximumCachedIcons) {
                iconCache.entries.iterator().run {
                    next()
                    remove()
                }
            }
        }
        return loaded
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
