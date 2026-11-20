// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

enum class PcmKind { PCM16, PCM24, PCM32, FLOAT }
data class PcmFormat(val rate: Int, val encoding: PcmKind, val channels: Int)
data class MixerOption(val format: PcmFormat, val direct: Boolean)
data class DecodedPcm(val format: PcmFormat, val sourceDepth: Int? = null, val fromDsd: Boolean = false)
data class MixerChoice(val option: MixerOption?, val exact: Boolean, val conversion: Boolean, val reason: String)

/** No sample-rate conversion. Declared capabilities, never inferred device quality. */
object UsbMixerSelector {
    fun select(supported: List<MixerOption>, source: DecodedPcm): MixerChoice {
        val pcm = source.format
        if (pcm.rate <= 0 || pcm.channels <= 0) return MixerChoice(null, false, false, "Chưa biết định dạng sau giải mã")
        val sameRate = supported.filter { it.direct && it.format.rate == pcm.rate && it.format.channels == pcm.channels }
        sameRate.firstOrNull { it.format == pcm }?.let {
            val lostDepth = source.sourceDepth != null && source.sourceDepth > 16 && pcm.encoding == PcmKind.PCM16
            return MixerChoice(it, !lostDepth, lostDepth,
                if (lostDepth) "Decoder đã xuất PCM16 từ nguồn trên 16 bit" else "Khớp định dạng sau giải mã")
        }
        val fallback = when {
            pcm.encoding == PcmKind.PCM24 -> sameRate.firstOrNull { it.format.encoding == PcmKind.PCM32 }
            source.fromDsd && pcm.encoding == PcmKind.FLOAT -> listOf(PcmKind.PCM32, PcmKind.PCM24, PcmKind.PCM16)
                .firstNotNullOfOrNull { kind -> sameRate.firstOrNull { it.format.encoding == kind } }
            else -> null
        }
        if (fallback != null) return MixerChoice(fallback, false, true, "Cần đổi ${pcm.encoding} → ${fallback.format.encoding}; không đổi tần số")
        return MixerChoice(null, false, false, when {
            supported.isEmpty() -> "Thiết bị/OEM không công bố cấu hình mixer USB"
            supported.none { it.direct } -> "Thiết bị không công bố chế độ mixer trực tiếp"
            else -> "Không có cấu hình khớp ${pcm.rate} Hz / ${pcm.encoding} / ${pcm.channels} kênh; giữ mixer hệ thống"
        })
    }
}

data class UsbEndpoint(val id: Int, val usb: Boolean)
data class ActualPcm(val format: PcmFormat, val deviceId: Int?, val usb: Boolean, val volume: Float,
    val bypassesDsp: Boolean = true)
data class UsbMixerStatus(val available: Boolean, val requested: Boolean, val verified: Boolean, val reason: String) {
    val label: String get() = if (verified) "Bit-perfect USB" else "USB · Mixer hệ thống"
}
interface MixerAccess {
    fun supported(deviceId: Int): List<MixerOption>
    fun preferred(deviceId: Int): MixerOption?
    fun set(deviceId: Int, option: MixerOption): Boolean
    fun clear(deviceId: Int): Boolean
}

/** Called under the Android route monitor's lock. The fake uses this same ownership/cleanup logic. */
class UsbMixerSession(private val access: MixerAccess) {
    private var owned: Pair<Int, MixerOption>? = null
    private var previous: MixerOption? = null
    private var failure: String? = null

    fun evaluate(api: Int, requested: Boolean, devices: List<UsbEndpoint>, source: DecodedPcm?,
        actual: ActualPcm?, sourceDirect: Boolean, dsdGainDb: Int): UsbMixerStatus {
        val usb = if (actual?.deviceId != null) devices.firstOrNull { it.id == actual.deviceId && it.usb }
            else devices.firstOrNull { it.usb }
        val supported = if (api >= 34 && usb != null) access.supported(usb.id) else emptyList()
        val available = api >= 34 && devices.any { it.usb && access.supported(it.id).isNotEmpty() }
        val blocked = when {
            api < 34 -> "Cần Android 14 trở lên"
            devices.none { it.usb } -> "Chưa kết nối thiết bị USB audio"
            usb == null -> "Âm thanh đang đi qua thiết bị khác"
            supported.isEmpty() -> "Thiết bị/OEM không công bố cấu hình mixer USB"
            !requested -> "Chế độ mixer trực tiếp đang tắt"
            !sourceDirect || actual?.bypassesDsp == false -> "Cần đường phát Source Direct đã bỏ qua mọi DSP"
            dsdGainDb != 0 -> "Cần đặt bù mức DSD về 0 dB; âm thanh đã đệm có thể còn gain"
            source == null -> "Chờ định dạng thực nhận từ decoder"
            else -> null
        }
        if (blocked != null) { release(); return UsbMixerStatus(available, requested, false, blocked) }
        val choice = UsbMixerSelector.select(supported, source!!)
        // A format conversion is reported, never silently introduced into the existing sink.
        if (!choice.exact || choice.option == null) {
            release(); return UsbMixerStatus(available, requested, false, choice.reason)
        }
        val desired = usb!!.id to choice.option
        if (owned != desired) {
            release()
            previous = access.preferred(usb.id)
            if (!access.set(usb.id, choice.option)) {
                previous = null
                return UsbMixerStatus(available, requested, false, "Hệ thống từ chối yêu cầu mixer USB")
            }
            owned = desired
        }
        val reason = when {
            failure != null -> failure!!
            access.preferred(usb.id) != choice.option -> "Mixer ưu tiên chưa được hệ thống xác nhận"
            actual == null -> "Chờ AudioTrack được tạo"
            !actual.usb || actual.deviceId != usb.id -> "AudioTrack chưa đi tới USB"
            actual.format != choice.option.format -> "Định dạng AudioTrack khác cấu hình đã chọn"
            actual.volume != 1f -> "Âm lượng số hoặc audio focus đang thay đổi mẫu"
            else -> "Đủ điều kiện từ mixer, AudioTrack, thiết bị USB và Source Direct"
        }
        val verified = failure == null && access.preferred(usb.id) == choice.option && actual != null &&
            actual.usb && actual.deviceId == usb.id && actual.format == choice.option.format && actual.volume == 1f
        return UsbMixerStatus(available, requested, verified, reason)
    }

    fun release() {
        val prior = owned ?: return
        owned = null
        val restore = previous; previous = null
        failure = if (!access.clear(prior.first)) "Chưa xoá được mixer ưu tiên; xem log" else null
        if (restore != null && !access.set(prior.first, restore)) failure = "Chưa khôi phục được mixer trước đó; xem log"
    }
}
