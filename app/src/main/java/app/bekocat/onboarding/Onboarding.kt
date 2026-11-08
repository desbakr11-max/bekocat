package app.bekocat.onboarding

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.bekocat.ai.ModelManager
import app.bekocat.editor.panels.ModelManagerList
import app.bekocat.ui.components.BekocatLogo
import app.bekocat.ui.components.tr
import app.bekocat.ui.theme.BkColors

object Onboarding {
    private fun prefs(c: Context) = c.getSharedPreferences("bekocat", Context.MODE_PRIVATE)
    fun done(c: Context) = prefs(c).getBoolean("onboarded", false)
    fun finish(c: Context) = prefs(c).edit().putBoolean("onboarded", true).apply()
}

/** First-run: language, what works offline, and optional model downloads (never forced). */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(BkColors.Background).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BekocatLogo(64.dp)
        when (step) {
            0 -> {
                Text("أهلًا في بيكوكات · Welcome to Bekocat", style = MaterialTheme.typography.headlineSmall)
                Text("اختر لغة الواجهة · Choose the interface language", color = BkColors.Muted)
                if (Build.VERSION.SDK_INT >= 33) {
                    val lm = ctx.getSystemService(LocaleManager::class.java)
                    val cur = lm.applicationLocales.get(0)?.language
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(cur == null || cur == "ar", { lm.applicationLocales = LocaleList.forLanguageTags("ar") }, { Text("العربية") })
                        FilterChip(cur == "en", { lm.applicationLocales = LocaleList.forLanguageTags("en") }, { Text("English") })
                    }
                } else Text("تتبع اللغة إعدادات الجهاز · Follows the device language", color = BkColors.Muted)
                Button(onClick = { step = 1 }) { Text(tr("التالي", "Next")) }
            }
            1 -> {
                Text(tr("كل شيء على جهازك", "Everything stays on your device"), style = MaterialTheme.typography.titleLarge)
                Card(tr("المونتاج، الموشن، الألوان، الصوت والتصدير تعمل بدون إنترنت ولا تحتاج أي تنزيل.",
                    "Editing, motion, color, audio and export work offline and need no download."))
                Card(tr("ميزات الذكاء الاصطناعي (الكابشن، إزالة الخلفية، فصل الصوت، المساعد) تعمل محليًا بعد تنزيل نماذجها مرة واحدة. لا حساب، لا اشتراك، لا علامة مائية.",
                    "AI features (captions, background removal, stem separation, assistant) run locally after a one-time model download. No account, no subscription, no watermark."))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { step = 0 }) { Text(tr("رجوع", "Back")) }
                    Button(onClick = { step = 2 }) { Text(tr("التالي", "Next")) }
                }
            }
            else -> {
                Text(tr("نماذج اختيارية", "Optional models"), style = MaterialTheme.typography.titleLarge)
                Text(tr("يمكنك تنزيلها الآن أو لاحقًا من الإعدادات أو عند أول استخدام. التنزيل يستمر بالخلفية.",
                    "Download now or later from Settings or on first use. Downloads continue in the background."), color = BkColors.Muted)
                ModelManagerList(ModelManager.shared(ctx), setOf(ModelManager.ASR, ModelManager.MATTING, ModelManager.SEPARATION))
                Button(onClick = { Onboarding.finish(ctx); onDone() }, Modifier.fillMaxWidth()) { Text(tr("ابدأ", "Start")) }
            }
        }
    }
}

@Composable
private fun Card(text: String) = Text(text, Modifier.fillMaxWidth().background(BkColors.Surface, RoundedCornerShape(12.dp)).padding(14.dp))
