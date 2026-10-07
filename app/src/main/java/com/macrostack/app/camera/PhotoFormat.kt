package com.macrostack.app.camera

/** Which files each stack frame produces. */
enum class PhotoFormat(val jpeg: Boolean, val raw: Boolean, val label: String) {
    JPEG(jpeg = true, raw = false, label = "JPEG"),
    RAW(jpeg = false, raw = true, label = "RAW (DNG)"),
    RAW_JPEG(jpeg = true, raw = true, label = "RAW + JPEG");

    companion object {
        fun fromName(name: String?): PhotoFormat = entries.firstOrNull { it.name == name } ?: JPEG
    }
}
