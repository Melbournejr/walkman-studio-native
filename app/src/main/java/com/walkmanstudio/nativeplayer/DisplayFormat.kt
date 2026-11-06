// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer

import java.text.NumberFormat
import java.util.Locale

private val vietnamese = Locale.forLanguageTag("vi-VN")

fun formatCount(count: Long): String = NumberFormat.getIntegerInstance(vietnamese).format(count)

fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "Chưa rõ kích thước"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(vietnamese, "%.1f %s", value, units[index])
}

fun formatScanState(state: String): String = when (state) {
    "ENQUEUED", "BLOCKED" -> "Đang chờ quét"
    "RUNNING" -> "Đang quét"
    "SUCCEEDED", "COMPLETE", "COMPLETED" -> "Đã quét xong"
    "CANCELLED" -> "Đã hủy quét"
    "FAILED", "ERROR" -> "Quét chưa hoàn tất"
    "INTERRUPTED" -> "Quét bị gián đoạn"
    "PERMISSION_LOST" -> "Cần cấp lại quyền đọc"
    else -> "Chưa quét"
}
