package com.iris.localai.util.io

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 2.7.2：设置 / 配置导出与导入（一个 JSON 文件，纯 JVM，单测）。与「导出备份」（整个 zip：对话、记忆、数据库）不同，
 * 这里只有用户配置：应用设置、智能体与记忆选项、各模型参数、角色卡（card.json，不含头像等资源）。
 * 不含模型文件；API 密钥等敏感项默认不导出（[SECRET_KEYS]），本机专属的路径授权从不导出（[DEVICE_KEYS]）。
 * 文件头带 [FORMAT]、[SCHEMA_VERSION] 与应用版本号；导入时按 [migrate] 逐版迁移，未知字段忽略，类型不对的单项跳过。
 */
object ConfigTransfer {
    const val FORMAT = "iridis-config"
    /** 1 = 2.7.2。改结构时加一，并在 [MIGRATIONS] 里写从上一版到这一版的迁移。 */
    const val SCHEMA_VERSION = 1

    /** 敏感项（字段名，任一分区）：默认不导出；导入时文件里有才覆盖。 */
    val SECRET_KEYS = setOf("apiKey")
    private val SECRET_LIKE = Regex("(?i)(api_?key|token|secret|password)$")
    /** 只对这台设备有意义（存储授权 URI、自选文件夹路径、迁移标记）：从不导出，导入也不覆盖。 */
    val DEVICE_KEYS = setOf("workspaceTreeUri", "customWorkspace", "backendsMigrated", "downloadSourceMigrated", "notesSeen")

    fun isSecret(key: String) = key in SECRET_KEYS || SECRET_LIKE.containsMatchIn(key)

    /** 分区名。 */
    const val SETTINGS = "settings"
    const val AGENT = "agent"
    const val MEMORY = "memory"
    const val MODEL_CONFIGS = "modelConfigs"
    const val CHARACTERS = "characters"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    /** 一张角色卡：文件夹 id + card.json 原文。 */
    data class Card(val id: String, val json: String)

    /** 去掉敏感项（[withSecrets] = false 时）与本机专属项。 */
    fun scrub(o: JsonObject, withSecrets: Boolean): JsonObject =
        JsonObject(o.filterKeys { k -> k !in DEVICE_KEYS && (withSecrets || !isSecret(k)) })

    fun <T> section(serializer: KSerializer<T>, value: T, withSecrets: Boolean): JsonObject =
        scrub(json.encodeToJsonElement(serializer, value).jsonObject, withSecrets)

    /**
     * 导出文本。[sections] = 分区名 → 已经 [section] 过的对象；[modelConfigs] = model_configs.json 的内容（可空）。
     */
    fun export(
        appVersion: String, versionCode: Int, sections: Map<String, JsonObject>, modelConfigs: JsonObject?, cards: List<Card>,
        withSecrets: Boolean, now: Long = System.currentTimeMillis(),
    ): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("schemaVersion", SCHEMA_VERSION)
            put("app", "IRIDIS")
            put("appVersion", appVersion)
            put("versionCode", versionCode)
            put("createdAt", now)
            put("includesSecrets", withSecrets)
            for ((k, v) in sections) put(k, v)
            if (modelConfigs != null) put(MODEL_CONFIGS, modelConfigs)
            put(CHARACTERS, buildJsonArray {
                for (c in cards) {
                    val card = runCatching { json.parseToJsonElement(c.json) }.getOrNull() ?: continue
                    add(buildJsonObject { put("id", c.id); put("card", card) })
                }
            })
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    class BadFile(msg: String) : IllegalArgumentException(msg)

    /** 读出来的文件（已迁移到当前 [SCHEMA_VERSION]）。 */
    data class Parsed(
        val schemaVersion: Int,
        val appVersion: String,
        val versionCode: Int,
        val createdAt: Long,
        val includesSecrets: Boolean,
        val root: JsonObject,
    ) {
        fun section(name: String): JsonObject? = root[name] as? JsonObject
        val modelConfigs: JsonObject? get() = root[MODEL_CONFIGS] as? JsonObject
        val cards: List<Card> get() = (root[CHARACTERS] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val card = o["card"] as? JsonObject ?: return@mapNotNull null
            Card(id, pretty.encodeToString(JsonObject.serializer(), card))
        }
    }

    /** 从版本 n 迁到 n+1 的步骤（键 = n）。目前只有 1，没有迁移；以后改字段名等写在这里。 */
    val MIGRATIONS: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        // 0（没有 schemaVersion 的手写 / 预发布文件）→ 1：结构相同。
        0 to { o -> o },
    )

    fun migrate(root: JsonObject, from: Int): JsonObject {
        var o = root
        var v = from
        while (v < SCHEMA_VERSION) { o = MIGRATIONS[v]?.invoke(o) ?: o; v++ }
        return o
    }

    /** 解析并迁移；不是设置文件时抛 [BadFile]。比当前新的版本照样读（不认识的字段忽略）。 */
    fun parse(text: String): Parsed {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw BadFile("json") }
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) throw BadFile("format")
        val v = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 0
        val m = migrate(root, v)
        return Parsed(
            schemaVersion = v,
            appVersion = (root["appVersion"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            versionCode = (root["versionCode"] as? JsonPrimitive)?.intOrNull ?: 0,
            createdAt = (root["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            includesSecrets = (root["includesSecrets"] as? JsonPrimitive)?.contentOrNull == "true",
            root = m,
        )
    }

    /** 合并结果：新值、用上的字段数、跳过的字段数（未知 / 类型不对 / 本机专属）。 */
    data class Merged<T>(val value: T, val applied: Int, val skipped: Int)

    /**
     * 把 [incoming] 的字段逐个合并进 [current]：只认 [current] 序列化后有的字段；本机专属项不动；敏感项只在
     * [incoming] 里非空时才覆盖；某一项类型不对就跳过那一项，其它照用。Pure.
     */
    fun <T> merge(serializer: KSerializer<T>, current: T, incoming: JsonObject?): Merged<T> {
        if (incoming == null) return Merged(current, 0, 0)
        var base = json.encodeToJsonElement(serializer, current).jsonObject
        var applied = 0; var skipped = 0
        for ((k, v) in incoming) {
            if (k !in base || k in DEVICE_KEYS) { skipped++; continue }
            if (isSecret(k) && (v as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()) continue
            if (base[k] == v) continue
            val next = JsonObject(base + (k to v))
            if (runCatching { json.decodeFromJsonElement(serializer, next) }.isSuccess) { base = next; applied++ } else skipped++
        }
        return Merged(json.decodeFromJsonElement(serializer, base), applied, skipped)
    }

    /** model_configs.json 的一项（模型 id → 参数）逐个解码；坏的跳过。 */
    fun <T> entries(serializer: KSerializer<T>, o: JsonObject?): Map<String, T> =
        o.orEmpty().mapNotNull { (k, v) -> runCatching { json.decodeFromJsonElement(serializer, v) }.getOrNull()?.let { k to it } }.toMap()

    fun parseObject(text: String?): JsonObject? = text?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
}
