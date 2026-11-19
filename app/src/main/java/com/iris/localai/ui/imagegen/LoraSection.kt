package com.iris.localai.ui.imagegen

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.imagegen.Backend
import com.iris.localai.imagegen.LoraCheck
import com.iris.localai.imagegen.LoraEntry
import com.iris.localai.imagegen.LoraFormat
import com.iris.localai.imagegen.LoraProblem
import com.iris.localai.imagegen.PreviewFrame
import com.iris.localai.ui.components.ConfirmDialog
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SliderRow
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.icons.IrisIcons
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/** LoRA 文案（导入结论、不合适的原因）。 */
object LoraText {
    fun format(f: LoraFormat?): String = when (f) {
        LoraFormat.KOHYA -> "LoRA"
        LoraFormat.PEFT -> "LoRA (PEFT)"
        LoraFormat.LOKR -> "LoKr"
        LoraFormat.LOHA -> "LoHa"
        null -> "LoRA"
    }

    fun problem(c: LoraCheck): String = I18n.s(
        when (c.problem) {
            LoraProblem.NOT_SAFETENSORS, null -> R.string.ig271_lora_e_file
            LoraProblem.NOT_LORA -> R.string.ig271_lora_e_notlora
            LoraProblem.FULL_MODEL -> R.string.ig271_lora_e_full
            LoraProblem.TEXT_ONLY -> R.string.ig271_lora_e_te
            LoraProblem.UNKNOWN_ARCH -> R.string.ig271_lora_e_arch
            LoraProblem.VIDEO -> R.string.ig271_lora_e_video
            LoraProblem.DTYPE -> R.string.ig271_lora_e_dtype
        },
    )

    /** 导入成功：格式、秩；文本编码器部分不生效；和当前模型不配时说一句。 */
    fun ok(c: LoraCheck, fitsCurrent: Boolean): String {
        val parts = mutableListOf(I18n.s(R.string.ig271_lora_ok, format(c.format), c.rank))
        if (c.textEncoderPart) parts += I18n.s(R.string.ig271_lora_textpart)
        if (!fitsCurrent) parts += I18n.s(R.string.ig271_lora_mismatch)
        return parts.joinToString("\n")
    }

    fun sub(e: LoraEntry): String =
        format(LoraFormat.entries.firstOrNull { it.name == e.format }) + (if (e.rank > 0) " · r${e.rank}" else "")
}

/** 生图页的 LoRA 一节：sd.cpp（GPU/CPU）模型可挂，NPU 模型给一句说明。 */
@Composable
internal fun LoraSection(s: ImageGenUi, vm: ImageGenViewModel) {
    val c = Iris.colors
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLora) }
    var deleting by remember { mutableStateOf<LoraEntry?>(null) }
    SettingsGroup {
        if (!s.loraSupported) {
            val npu = s.model.backend == Backend.QNN || s.model.sdq
            Text(I18n.s(if (npu) R.string.ig271_lora_npu else R.string.ig271_lora_unsupported), style = IrisType.secondary, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12))
            return@SettingsGroup
        }
        for (e in s.lorasHere) {
            SwitchRow(e.name, e.on, subtitle = LoraText.sub(e)) { on -> vm.setLoraOn(e.id, on) }
            if (e.on) {
                SliderRow(I18n.s(R.string.ig271_lora_strength), e.scale, 0f..2f, "%.2f".format(e.scale), steps = 39) { v -> vm.setLoraScale(e.id, v) }
                e.steps?.let { st ->
                    Field(st.toString(), I18n.s(R.string.ig271_lora_steps), number = true) { v -> vm.setLoraSteps(e.id, v) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                IrisButton(I18n.s(R.string.ig271_lora_delete), onClick = { deleting = e }, style = IrisButtonStyle.Text, compact = true, icon = IrisIcons.Trash)
            }
        }
        val other = s.loras.size - s.lorasHere.size
        if (other > 0) Text(I18n.s(R.string.ig271_lora_other, other), style = IrisType.caption, color = c.textTertiary,
            modifier = Modifier.padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space6))
        Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = IrisDimens.space12)) {
            IrisButton(I18n.s(R.string.ig271_lora_import), onClick = { vm.clearLoraMsg(); pick.launch(arrayOf("*/*")) },
                style = IrisButtonStyle.Tonal, compact = true, icon = IrisIcons.Plus, enabled = !s.loraBusy)
            s.loraMsg?.let { Text(it, style = IrisType.caption, color = c.textSecondary, modifier = Modifier.padding(top = IrisDimens.space6)) }
        }
    }
    deleting?.let { e ->
        ConfirmDialog(
            title = I18n.s(R.string.ig271_lora_delete_q),
            message = e.name,
            confirmText = I18n.s(R.string.ig271_lora_delete),
            onConfirm = { vm.deleteLora(e.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/** 生图中的实时预览（低清，块状模糊是正常的）。 */
@Composable
internal fun LivePreview(f: PreviewFrame) {
    val img = remember(f) { Bitmap.createBitmap(f.argb, f.width, f.height, Bitmap.Config.ARGB_8888).asImageBitmap() }
    Image(img, null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().aspectRatio(f.width.toFloat() / f.height).clip(IrisRadius.mediumShape))
}
