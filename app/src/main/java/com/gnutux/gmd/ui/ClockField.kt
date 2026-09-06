package com.gnutux.gmd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gnutux.gmd.R
import androidx.compose.ui.res.stringResource

/**
 * حدٌّ زمنيٌّ في ثلاثةِ حقولٍ رقميّة: ساعاتٌ ودقائقُ وثوانٍ.
 *
 * كانَ حقلاً واحداً يُكتَبُ فيه `1:05:20` بيدِ صاحبِه، فيُخطئُ في النقطتَينِ أو
 * يكتبُ ثوانيَ مجرَّدةً ولا يدري أقُبِلَت أم لا. والثلاثةُ تُلزِمُه الصيغةَ من
 * أصلِها، ولوحةُ المفاتيحِ رقميّةٌ فلا يُكتَبُ فيها حرف.
 *
 * والقيمةُ المرفوعةُ تبقى نصّاً على صيغةِ `H:MM:SS` كما كانت، فلا يتغيّرُ ما
 * يقرؤه [com.gnutux.gmd.download.Section] ولا فحصُ الحدَّين. وإن خلت الحقولُ
 * الثلاثةُ جميعاً رُفِعَ نصٌّ فارغٌ — وهو ما يعنيه «بلا حدّ».
 *
 * والصفُّ من اليسارِ إلى اليمينِ دائماً: الساعةُ قبلَ الدقيقةِ قبلَ الثانية عُرفٌ
 * مطَّرِدٌ في القراءةِ لا يُعكَسُ بلغةِ الواجهة، كما لا يُعكَسُ مقبضُ الزمنِ نفسُه.
 */
@Composable
fun ClockField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val parts = splitClock(value)

    fun emit(h: String, m: String, s: String) {
        if (h.isBlank() && m.isBlank() && s.isBlank()) { onValueChange(""); return }
        val hh = h.toIntOrNull() ?: 0
        val mm = m.toIntOrNull() ?: 0
        val ss = s.toIntOrNull() ?: 0
        onValueChange("%d:%02d:%02d".format(hh, mm, ss))
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Unit3(parts.first, R.string.clock_hours, enabled, isError, Modifier.weight(1f)) {
                emit(it, parts.second, parts.third)
            }
            Unit3(parts.second, R.string.clock_minutes, enabled, isError, Modifier.weight(1f)) {
                emit(parts.first, it, parts.third)
            }
            Unit3(parts.third, R.string.clock_seconds, enabled, isError, Modifier.weight(1f)) {
                emit(parts.first, parts.second, it)
            }
        }
    }
}

/** خانةٌ واحدة: رقمانِ لا غير، ولا حرفَ يدخلُها. */
@Composable
private fun Unit3(
    value: String,
    placeholder: Int,
    enabled: Boolean,
    isError: Boolean,
    modifier: Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(2)
            onValueChange(digits)
        },
        placeholder = {
            Text(stringResource(placeholder), style = MaterialTheme.typography.labelSmall)
        },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        textStyle = centeredFieldStyle,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** يفكُّ `H:MM:SS` أو `M:SS` أو ثوانيَ مجرَّدةً إلى خاناتِه الثلاث. */
private fun splitClock(value: String): Triple<String, String, String> {
    val text = value.trim()
    if (text.isEmpty()) return Triple("", "", "")
    val parts = text.split(':').map { it.trim() }
    return when (parts.size) {
        3 -> Triple(parts[0], parts[1], parts[2])
        2 -> Triple("", parts[0], parts[1])
        else -> {
            // ثوانٍ مجرَّدةٌ كما كانت تُقبَلُ من قبل: تُوزَّعُ على الخانات
            val total = parts[0].toIntOrNull() ?: return Triple("", "", parts[0])
            Triple(
                (total / 3600).takeIf { it > 0 }?.toString().orEmpty(),
                ((total % 3600) / 60).toString(),
                (total % 60).toString(),
            )
        }
    }
}
