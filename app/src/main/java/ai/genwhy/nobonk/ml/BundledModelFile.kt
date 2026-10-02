package ai.genwhy.nobonk.ml

import android.content.Context
import java.io.File

/** App-private, non-backed-up model copies; ORT receives a path, never a model-sized ByteArray. */
internal object BundledModelFile {
    fun load(context: Context, modelName: String, checkActive: () -> Unit = {}): ModelFileCache.Model =
        ModelFileCache(File(context.noBackupFilesDir, "onnx-model-cache"))
            .load({ context.assets.open(modelName) }, checkActive)
}
