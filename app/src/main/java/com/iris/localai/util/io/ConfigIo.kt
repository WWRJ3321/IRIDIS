package com.iris.localai.util.io

import com.iris.localai.AppContainer
import com.iris.localai.agent.core.AgentSettings
import com.iris.localai.character.CardSpec
import com.iris.localai.data.AppSettings
import com.iris.localai.data.ModelOverrides
import com.iris.localai.memory.MemorySettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.InputStream
import java.io.OutputStream

/** 2.7.2：设置导出 / 导入的读写（App 侧；格式与迁移在纯的 [ConfigTransfer]）。 */
object ConfigIo {
    data class ImportResult(val settings: Int, val cards: Int, val skipped: Int)

    /** 写出 JSON；返回导出的角色卡张数。 */
    fun export(c: AppContainer, out: OutputStream, appVersion: String, versionCode: Int, withSecrets: Boolean): Int {
        val sections = linkedMapOf(
            ConfigTransfer.SETTINGS to ConfigTransfer.section(AppSettings.serializer(), c.settings.current, withSecrets),
            ConfigTransfer.AGENT to ConfigTransfer.section(AgentSettings.serializer(), c.agent.settings.current, withSecrets),
            ConfigTransfer.MEMORY to ConfigTransfer.section(MemorySettings.serializer(), c.agent.memorySettings.current, withSecrets),
        )
        val models = c.modelConfigs.configs.value
        val modelJson: JsonObject = buildJsonObject {
            for ((id, o) in models) put(id, ConfigTransfer.section(ModelOverrides.serializer(), o, withSecrets))
        }
        val cards = runCatching { c.characters.list() }.getOrDefault(emptyList()).map { ConfigTransfer.Card(it.id, it.card.toJson()) }
        val text = ConfigTransfer.export(appVersion, versionCode, sections, modelJson, cards, withSecrets)
        out.write(text.toByteArray(Charsets.UTF_8))
        return cards.size
    }

    /** 读文件头（确认弹窗用）；不是设置文件抛 [ConfigTransfer.BadFile]。 */
    fun inspect(input: InputStream): ConfigTransfer.Parsed = ConfigTransfer.parse(input.readBytes().toString(Charsets.UTF_8))

    /** 应用到当前设置；角色卡按 id 覆盖或新建（头像等资源不在文件里，已有的保留）。 */
    fun apply(c: AppContainer, p: ConfigTransfer.Parsed): ImportResult {
        var applied = 0; var skipped = 0
        ConfigTransfer.merge(AppSettings.serializer(), c.settings.current, p.section(ConfigTransfer.SETTINGS)).let { m ->
            c.settings.update { m.value }; applied += m.applied; skipped += m.skipped
        }
        ConfigTransfer.merge(AgentSettings.serializer(), c.agent.settings.current, p.section(ConfigTransfer.AGENT)).let { m ->
            c.agent.settings.update { m.value }; applied += m.applied; skipped += m.skipped
        }
        ConfigTransfer.merge(MemorySettings.serializer(), c.agent.memorySettings.current, p.section(ConfigTransfer.MEMORY)).let { m ->
            c.agent.memorySettings.update { m.value }; applied += m.applied; skipped += m.skipped
        }
        val models = p.modelConfigs
        val decoded = ConfigTransfer.entries(ModelOverrides.serializer(), models)
        skipped += (models?.size ?: 0) - decoded.size
        for ((id, o) in decoded) { c.modelConfigs.update(id) { o }; applied++ }
        var cards = 0
        for (card in p.cards) {
            val parsed = runCatching { CardSpec.parse(card.json) }.getOrNull()
            if (parsed == null) { skipped++; continue }
            if (runCatching { c.characters.save(parsed, card.id) }.isSuccess) cards++ else skipped++
        }
        return ImportResult(applied, cards, skipped)
    }
}
