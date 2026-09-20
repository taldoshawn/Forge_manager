package com.forgemanager.app.core.file

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

private const val EXTRA_LOCATION_JSON = "com.forgemanager.app.extra.FILE_LOCATION"
private const val EXTRA_DISPLAY_NAME = "com.forgemanager.app.extra.DISPLAY_NAME"

fun Intent.putFileLocation(location: FileLocation, displayName: String? = null): Intent = apply {
    putExtra(EXTRA_LOCATION_JSON, encodeFileLocation(location))
    displayName?.let { putExtra(EXTRA_DISPLAY_NAME, it) }
}

fun Intent.readFileLocation(): FileLocation? =
    getStringExtra(EXTRA_LOCATION_JSON)?.let(::decodeFileLocation)
        ?: getStringExtra("path")?.let(FileLocation::Direct)

fun Intent.fileDisplayName(): String? = getStringExtra(EXTRA_DISPLAY_NAME)

private fun encodeFileLocation(location: FileLocation): String = JSONObject().apply {
    when (location) {
        is FileLocation.Direct -> {
            put("type", "direct")
            put("path", location.path)
        }
        is FileLocation.Archive -> {
            put("type", "archive")
            put("archivePath", location.archivePath)
            put("entryPath", location.entryPath)
        }
        is FileLocation.Saf -> {
            put("type", "saf")
            put("documentUri", location.documentUri)
            put("treeUri", location.treeUri)
            put("displayPath", location.displayPath)
            put("parents", JSONArray().apply {
                location.parents.forEach { parent ->
                    put(JSONObject().put("documentUri", parent.documentUri).put("displayPath", parent.displayPath))
                }
            })
        }
    }
}.toString()

private fun decodeFileLocation(raw: String): FileLocation? = runCatching {
    val json = JSONObject(raw)
    when (json.getString("type")) {
        "direct" -> FileLocation.Direct(json.getString("path"))
        "archive" -> FileLocation.Archive(json.getString("archivePath"), json.optString("entryPath"))
        "saf" -> {
            val parentsJson = json.optJSONArray("parents") ?: JSONArray()
            val parents = ArrayList<FileLocation.SafParent>(parentsJson.length())
            for (index in 0 until parentsJson.length()) {
                val value = parentsJson.getJSONObject(index)
                parents += FileLocation.SafParent(value.getString("documentUri"), value.getString("displayPath"))
            }
            FileLocation.Saf(
                documentUri = json.getString("documentUri"),
                treeUri = json.getString("treeUri"),
                parents = parents,
                displayPath = json.getString("displayPath")
            )
        }
        else -> null
    }
}.getOrNull()
