package app.bekocat.settings

import android.app.LocaleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bekocat.R
import app.bekocat.ui.components.BekocatLogo
import app.bekocat.ui.theme.BkColors

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(BkColors.Background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        }

        Section {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            if (Build.VERSION.SDK_INT >= 33) {
                val lm = ctx.getSystemService(LocaleManager::class.java)
                val current = lm.applicationLocales.get(0)?.language
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(current == null || current == "ar", { lm.applicationLocales = LocaleList.forLanguageTags("ar") }, { Text(stringResource(R.string.lang_ar)) })
                    FilterChip(current == "en", { lm.applicationLocales = LocaleList.forLanguageTags("en") }, { Text(stringResource(R.string.lang_en)) })
                }
            } else {
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS))
                }) { Text(stringResource(R.string.settings_language_system)) }
            }
        }

        Section {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BekocatLogo(36.dp)
                Text(stringResource(R.string.settings_free), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Section {
            Text(app.bekocat.ui.components.tr("نماذج الذكاء الاصطناعي", "AI models"), style = MaterialTheme.typography.titleMedium)
            app.bekocat.editor.panels.ModelManagerList(app.bekocat.ai.ModelManager.shared(ctx))
        }

        Section {
            Text(app.bekocat.ui.components.tr("التخزين", "Storage"), style = MaterialTheme.typography.titleMedium)
            var cache by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ctx.cacheDir.walkTopDown().sumOf { it.length() }) }
            Text(app.bekocat.ui.components.tr("ذاكرة مؤقتة (معاينات، صوت محوّل، تصديرات مؤقتة): ", "Cache (previews, conformed audio, temp exports): ") + app.bekocat.ai.ModelManager.human(cache), color = BkColors.Muted)
            OutlinedButton(onClick = { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }; cache = ctx.cacheDir.walkTopDown().sumOf { it.length() } }) {
                Text(app.bekocat.ui.components.tr("مسح الذاكرة المؤقتة (لا يمس المشاريع)", "Clear cache (projects untouched)"))
            }
        }

        Section {
            Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.licenses_body), color = BkColors.Muted, style = MaterialTheme.typography.bodyMedium)
            val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
            Text(stringResource(R.string.settings_version, version), color = BkColors.Muted)
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }) { Text("App info") }
        }
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(BkColors.Surface, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
