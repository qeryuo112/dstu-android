package com.deepstudent.buildlogic.architecture

import com.google.gson.Gson
import java.io.File

internal fun scanSourceDirectories(modelJson: String, mode: String): List<File> {
    if (modelJson.isBlank() || modelJson == "{}") {
        return emptyList()
    }
    val model = Gson().fromJson(modelJson, ScanModel::class.java) ?: return emptyList()
    val sourceSets = if (mode == "architecture") {
        model.architectureSourceSets
    } else {
        model.complexitySourceSets
    }.toSet()
    return model.modules
        .flatMap { it.sources }
        .filter { it.sourceSet in sourceSets }
        .map { File(it.dir) }
        .filter { it.isDirectory }
        .distinct()
}
