package com.gnutux.gmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gnutux.gmd.R

/**
 * حدٌّ زمنيٌّ في ثلاثِ خاناتٍ رقميّة: ساعاتٌ ودقائقُ وثوانٍ.
 *
 * كانَ حقلاً واحداً يُكتَبُ فيه `1:05:20` بيدِ صاحبِه، فيُخطئُ في النقطتَينِ أو
 * يكتبُ ثوانيَ مجرَّدةً ولا يدري أقُبِلَت أم لا. والثلاثةُ تُلزِمُه الصيغةَ من
 * أصلِها، ولوحةُ المفاتيحِ رقميّةٌ فلا يُكتَبُ فيها حرف.
 *
 * وثلاثةُ أشياءَ تجعلُ الخانةَ صالحةً للاستعمالِ بإصبعٍ على هاتف:
 *
 * **الأوّل** أنّ نصَّ الخانةِ يُحدَّدُ كلُّه عندَ النقرِ عليها، فالكتابةُ تُبدِّلُه
 * ولا تُلحَقُ به. من أرادَ `20` بدلَ `05` كتبَ رقمَه ومضى، ولم يمسح رقمَين أوّلاً.
 *
 * **والثاني** أنّ الخانةَ تُفرَغُ فتبقى فارغة. وكانَ نصُّها يُشتَقُّ من القيمةِ
 * المجموعةِ (`H:MM:SS`)، وتلك لا تخلو من أصفارٍ أبداً، فيعودُ الصفرُ إلى الخانةِ
 * لحظةَ مسحِه ولا يستطيعُ صاحبُها إفراغَها. فصارَ لكلِّ خانةٍ نصُّها المستقلّ،
 * والقيمةُ المرفوعةُ تُبنى منها.
 *
 * **والثالث** أنّها مرسومةٌ بـ`BasicTextField` لا `OutlinedTextField`: ذلك يفرضُ
 * حشوةً داخليّةً بمقدارِ 16dp في كلِّ جانبٍ لا سبيلَ إلى تصغيرِها، وثلاثُ خاناتٍ
 * في نصفِ عرضِ الشاشةِ لا يبقى في كلٍّ منها للرقمِ إلّا فُتاتٌ فيُقتَطَعُ ما فيها
 * — وهو ما ظهرَ في خانةِ الدقائق.
 *
 * والقيمةُ المرفوعةُ تبقى نصّاً على صيغةِ `H:MM:SS` كما كانت، فلا يتغيّرُ ما
 * يقرؤه [com.gnutux.gmd.download.Section] ولا فحصُ الحدَّين. وإن خلت الخاناتُ
 * الثلاثُ جميعاً رُفِعَ نصٌّ فارغٌ — وهو ما يعنيه «بلا حدّ».
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
    // نصُّ كلِّ خانةٍ مستقلٌّ عن القيمةِ المجموعة، وإلّا لم تُفرَغ خانةٌ أبداً
    var boxes by remember { mutableStateOf(splitClock(value)) }
    // ما رفعناه آخرَ مرّة: به نعرفُ أنّ القيمةَ تغيّرت من خارجِنا — كتعبئةِ المدّةِ
    // تلقائيّاً — فنُعيدُ بناءَ الخانات، ولا نفعلُ ذلك مع تغييرٍ من صاحبِها
    var emitted by remember { mutableStateOf(value) }
    if (value != emitted) {
        boxes = splitClock(value)
        emitted = value
    }

    fun push(next: Triple<String, String, String>) {
        boxes = next
        val (h, m, s) = next
        val text = if (h.isBlank() && m.isBlank() && s.isBlank()) {
            ""
        } else {
            "%d:%02d:%02d".format(
                h.toIntOrNull() ?: 0, m.toIntOrNull() ?: 0, s.toIntOrNull() ?: 0,
            )
        }
        emitted = text
        onValueChange(text)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DigitBox(boxes.first, R.string.clock_hours, enabled, isError, Modifier.weight(1f)) {
                push(boxes.copy(first = it))
            }
            DigitBox(boxes.second, R.string.clock_minutes, enabled, isError, Modifier.weight(1f)) {
                push(boxes.copy(second = it))
            }
            DigitBox(boxes.third, R.string.clock_seconds, enabled, isError, Modifier.weight(1f)) {
                push(boxes.copy(third = it))
            }
        }
    }
}

/** خانةٌ واحدة: رقمانِ لا غير، ولا حرفَ يدخلُها. */
@Composable
private fun DigitBox(
    value: String,
    placeholder: Int,
    enabled: Boolean,
    isError: Boolean,
    modifier: Modifier,
    onValueChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // `TextFieldValue` لا نصٌّ مجرَّد: التحديدُ جزءٌ منه، وبه يُحدَّدُ المحتوى عندَ
    // النقر
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))

    val border = when {
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, border, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = field,
            onValueChange = { input ->
                val digits = input.text.filter { it.isDigit() }.take(2)
                field = input.copy(
                    text = digits,
                    selection = TextRange(digits.length.coerceAtMost(input.selection.end)),
                )
                if (digits != value) onValueChange(digits)
            },
            enabled = enabled,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .onFocusChanged { state ->
                    // النقرُ يُحدِّدُ ما في الخانة، فالكتابةُ تُبدِّلُه لا تُلحَقُ به
                    if (state.isFocused) {
                        field = field.copy(selection = TextRange(0, field.text.length))
                    } else {
                        keyboard?.hide()
                    }
                },
        )
        if (value.isEmpty()) {
            Text(
                stringResource(placeholder),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** يفكُّ `H:MM:SS` أو `M:SS` أو ثوانيَ مجرَّدةً إلى خاناتِه الثلاث. */
private fun splitClock(value: String): Triple<String, String, String> {
    val text = value.trim()
    if (text.isEmpty()) return Triple("", "", "")
    val parts = text.split(':').map { it.trim() }
    return when (parts.size) {
        3 -> Triple(parts[0].trimZero(), parts[1], parts[2])
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

/** ساعةُ الصفرِ لا تُعرَض: `0:00:24` تُقرَأُ في الخاناتِ فارغةَ الساعات. */
private fun String.trimZero(): String = if (toIntOrNull() == 0) "" else this
