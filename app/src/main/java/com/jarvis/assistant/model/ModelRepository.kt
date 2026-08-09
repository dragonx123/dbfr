package com.jarvis.assistant.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Locates and manages the on-device `.litertlm` model file.
 *
 * Models are 500MB-several GB, so they are never bundled in the APK or
 * committed to git (see .gitignore). Instead the user either:
 *  1. Picks a `.litertlm` file already on their device (Storage Access
 *     Framework), which gets copied into app-private storage, or
 *  2. Pushes one directly with `adb push model.litertlm
 *     /data/local/tmp/jarvis/model.litertlm`, which is used in place.
 *
 * Recommended models: https://huggingface.co/litert-community
 * (e.g. Gemma3-1B-IT for a good speed/quality tradeoff on phones, or
 * FunctionGemma for the best tool-calling behavior with JarvisTools).
 */
class ModelRepository(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    private val importedModelFile: File
        get() = File(modelsDir, "model.litertlm")

    /** Path adb can push a model to; checked as a fallback for the imported copy. */
    private val adbPushedModelFile: File
        get() = File("/data/local/tmp/jarvis/model.litertlm")

    /** Returns the absolute path to a usable model file, or null if none is set up yet. */
    fun currentModelPath(): String? = when {
        importedModelFile.exists() -> importedModelFile.absolutePath
        adbPushedModelFile.exists() -> adbPushedModelFile.absolutePath
        else -> null
    }

    fun hasModel(): Boolean = currentModelPath() != null

    /** Copies a user-picked model file (via SAF) into app-private storage. */
    suspend fun importModel(uri: Uri, onProgress: (bytesCopied: Long) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(modelsDir, "model.litertlm.part")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20) // 1MB
                        var total = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } >= 0) {
                            output.write(buffer, 0, read)
                            total += read
                            onProgress(total)
                        }
                    }
                } ?: error("Could not open the selected file")
                if (!tmp.renameTo(importedModelFile)) {
                    tmp.copyTo(importedModelFile, overwrite = true)
                    tmp.delete()
                }
                importedModelFile.absolutePath
            }
        }

    fun deleteImportedModel() {
        importedModelFile.delete()
    }

    /** Writable cache dir passed to EngineConfig to speed up subsequent model loads. */
    fun engineCacheDir(): String = context.cacheDir.path
}
