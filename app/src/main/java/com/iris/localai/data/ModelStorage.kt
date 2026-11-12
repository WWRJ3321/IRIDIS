package com.iris.localai.data

import java.io.File

/**
 * 2.5.2: the one place that resolves a model folder on the device, used by every download *and*
 * import (LLM: ModelRepository.import / importFolder / MnnHub; 生图; voice packs and imported TTS;
 * translation packs). Before, each store called [ModelDirs.resolve] with 全部文件访问 as the only
 * switch, so with the storage location (工作区位置) in 下载 but without that permission, imported
 * models still went to Android/data. Now the configured storage root is followed too.
 */
object ModelStorage {
    /** The app's storage root (AgentContainer.appRoot); set by AppContainer. Null = unknown (2.1.0 rule). */
    @Volatile var storageRoot: () -> File? = { null }

    fun location(privateDir: File, sub: String = ModelDirs.SUB): ModelDirs.Location {
        val downloads = runCatching { android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        return ModelDirs.resolve(
            allFilesAccess = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false),
            storageState = runCatching { android.os.Environment.getExternalStorageState() }.getOrNull(),
            downloadsDir = downloads,
            privateDir = privateDir,
            sub = sub,
            followStorage = ModelDirs.followsStorage(runCatching { storageRoot() }.getOrNull(), downloads),
        )
    }
}
