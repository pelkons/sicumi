package app.sicumi.dictation

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * Подписи системного UI для иллюстраций мастера настройки диктовки.
 * Это копии надписей самого Android (на языке системы телефона), а не тексты Sicumi,
 * поэтому они не в strings.xml: приложение всегда на иврите, а система может быть на английском.
 */
data class SystemLabels(
    val rtl: Boolean,
    /** Раздел спецвозможностей, где лежат сторонние сервисы. */
    val section: String,
    /** Главный переключатель на странице сервиса. */
    val toggle: String,
    val shortcut: String,
    val fullControlTitle: String,
    val allow: String,
    val deny: String,
    val micTitle: String,
    val micWhileUsing: String,
    val micOnlyThisTime: String,
    val micDeny: String,
    val appInfo: String,
    val restrictedMenu: String,
)

/** Переходы в системные настройки, нужные для диктовки. */
object SystemSettings {

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    /**
     * Экран спецвозможностей. Дополнительные параметры просят «Настройки» подсветить наш сервис
     * в списке — работает не на всех прошивках, там где не работает, просто игнорируется.
     */
    fun openAccessibility(context: Context) {
        val key = ComponentName(context, DictationService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
        start(context, intent)
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, intent)
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Начиная с Android 13 система может заблокировать спецвозможности («Restricted setting»). */
    val canBeRestricted: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** Блокировка почти наверняка есть, если APK установлен из файла, а не из магазина. */
    fun likelyRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val source = context.packageManager.getInstallSourceInfo(context.packageName).packageSource
            source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        } catch (e: Exception) {
            false
        }
    }

    fun labels(serviceName: String): SystemLabels {
        val language = Resources.getSystem().configuration.locales[0].language
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val name = "⁨$serviceName⁩"
        val app = "⁨Sicumi⁩"
        return if (language == "iw" || language == "he") {
            SystemLabels(
                rtl = true,
                section = if (samsung) "אפליקציות מותקנות" else "אפליקציות שהורדו",
                toggle = if (samsung) "פועל" else "שימוש ב-$name",
                shortcut = "קיצור הדרך של $name",
                fullControlTitle = "לאפשר ל-$name שליטה מלאה במכשיר?",
                allow = "אישור",
                deny = "דחייה",
                micTitle = "לאפשר ל-$app להקליט אודיו?",
                micWhileUsing = "בזמן השימוש באפליקציה",
                micOnlyThisTime = "רק הפעם",
                micDeny = "אין אישור",
                appInfo = "פרטי האפליקציה",
                restrictedMenu = "התרת הגדרות מוגבלות",
            )
        } else {
            SystemLabels(
                rtl = false,
                section = if (samsung) "Installed apps" else "Downloaded apps",
                toggle = if (samsung) "On" else "Use $name",
                shortcut = "$name shortcut",
                fullControlTitle = "Allow “$name” to have full control of your device?",
                allow = "Allow",
                deny = "Deny",
                micTitle = "Allow $app to record audio?",
                micWhileUsing = "While using the app",
                micOnlyThisTime = "Only this time",
                micDeny = "Don’t allow",
                appInfo = "App info",
                restrictedMenu = "Allow restricted settings",
            )
        }
    }
}
