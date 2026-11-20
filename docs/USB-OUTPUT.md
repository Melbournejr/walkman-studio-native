# GĐ11 — Đo và cấu hình đường ra USB

API Android 34+; mặc định tắt. Tên công tắc trung tính cho tới khi thiết bị xác nhận đầy đủ. Không thêm thư viện. Không đổi FIR, EQ, DSD, tốc độ phát hay tự resample. Android 26–33 giữ đường ra cũ.

Ngoại lệ quyền đã được người dùng chấp thuận trong phiên: thêm duy nhất `MODIFY_AUDIO_SETTINGS` (normal permission). Android yêu cầu quyền này cho cả đặt và xoá mixer; yêu cầu ban đầu không thêm quyền không thể đồng thời thực hiện hai API đó. Không thay các quyền OTA stage10.

Luồng: decoder → callback cấu hình sink → chọn định dạng thuần Kotlin → yêu cầu mixer → provider Media3 gốc tạo AudioTrack → đo đối tượng AudioTrack và thiết bị routed → đánh giá trạng thái. Không suy tần số ra từ sampleRates của DAC.

Xác nhận đòi hỏi: thuộc tính mixer ưu tiên thực đọc lại đúng behavior/định dạng; AudioTrack thật đúng rate/encoding/channels; routedDevice đúng USB; Source Direct; bù DSD 0 dB; âm lượng số của AudioOutput là 1. DSP đã lưu nhưng bị Source Direct bypass không được tính là đang chạy. Gain DSD đã đệm chặn xác nhận cho tới AudioTrack mới. Từ chối xác nhận khi biết nguồn >16 bit nhưng decoder trả PCM16.

Nếu chọn được cấu hình cần đổi encoding (24→32 hoặc DSD float→int), log nêu chuyển đổi, giữ mixer hệ thống; GĐ11 không thêm bộ chuyển đổi mẫu để ép khớp. Nếu sink hiện tại chuyển 24-bit sang float/16-bit thì cũng không khẳng định đường ra khớp. Các phép đo chỉ mô tả ngõ ra ứng dụng, chưa đo tín hiệu điện hay dữ liệu USB.

`Xuất log trình phát` xuất JSONL cục bộ: `output_route.fields` chứa thiết bị, capabilities, mixer supported/preferred, AudioTrack thực, routedDevice, API; `decoder_output.fields` chứa định dạng tại đầu vào sink, sourceBitDepth nếu biết, trackId. Sự kiện chuyển bài có `awaiting_decoder` với giá trị null, không dùng giá trị bài cũ. FLAC lấy bit depth từ encoding nguồn hoặc STREAMINFO; codec nén khác không biết thì null. Không sửa metadata/decoder dựa trên số đo.

Mỗi phiên sở hữu cấu hình mixer, xoá khi tắt/đổi thiết bị/rút DAC/tắt DSP bypass/dừng service; khôi phục thuộc tính có trước nếu có. Listener cập nhật trạng thái bằng kết quả đọc lại; không coi lời gọi set thành công là bằng chứng hoàn tất.

Nguồn API đã đối chiếu:

- [Android AudioManager: preferred/supported mixer](https://developer.android.com/reference/android/media/AudioManager#setPreferredMixerAttributes(android.media.AudioAttributes,android.media.AudioDeviceInfo,android.media.AudioMixerAttributes)). API có **3** tham số; behavior nằm trong `AudioMixerAttributes`, không có tham số thứ tư.
- [AOSP Android 14 AudioManager.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/media/java/android/media/AudioManager.java): annotation `RequiresPermission(MODIFY_AUDIO_SETTINGS)`, ownership theo UID, usage MEDIA, chỉ USB được cho phép đặt mixer.
- [AudioMixerAttributes](https://developer.android.com/reference/android/media/AudioMixerAttributes).
- [Media3 1.11.1 AudioTrackAudioOutputProvider](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutputProvider.java): provider gốc, public AudioTrackAudioOutput.getAudioTrack; không reflection, không API deprecated.

## Not verified

Chưa có AudioManager/OEM, USB DAC hoặc Xiaomi 14 Pro thật trên máy chủ. Host/Robolectric chỉ kiểm tra logic, telemetry, fixture AudioTrack và UI; không chứng minh mẫu USB, âm lượng phần cứng hay độ chính xác đồng hồ DAC. Listener/rút nóng/đổi rate giữa bài cần máy thật.

## Cách tôi tự kiểm trên máy thật

1. Cài cập nhật đè; chọn DAC USB làm đầu ra. Hạ âm lượng DAC/ampli trước. Vào Cài đặt, bật Source Direct, bù DSD 0 dB rồi bật mixer USB và xác nhận cảnh báo.
2. Phát lần lượt WAV16/44,1; FLAC24/96; FLAC24/192; DSD→PCM88,2 float. Xuất log sau từng nhóm. So sourceBitDepth với decoder pcmEncoding, AudioTrack, supported/preferred và routedDevice; giá trị không biết phải null.
3. Thử EQ, bù DSD +3/+6, tắt công tắc, chuyển Bluetooth, rút nóng DAC, dừng service. Trạng thái phải mất xác nhận; log phải ghi xoá/khôi phục. Nhớ đưa gain về 0 và khởi tạo phiên phát mới khi kiểm lại DSD đã đệm.
4. Chạy `adb shell dumpsys audio > audio.txt` và `adb shell dumpsys media.audio_flinger > audio_flinger.txt` trong lúc phát. Đối chiếu thiết bị, thread/output, rate/format với log; không suy format điện từ tên thread.
5. Nếu DAC có đèn/màn hiển thị tần số, ghi ảnh khi đổi 44,1→96→192→88,2 kHz, đối chiếu hướng dẫn của đúng DAC. Đèn rate chỉ xác minh rate thiết bị báo, không đủ chứng minh dữ liệu mẫu giữ nguyên. Gửi cùng log và dumpsys.
6. Thử Android <34, DAC không công bố cấu hình, loa và Bluetooth: công tắc phải có lý do không khả dụng, phát cũ vẫn dùng được. Kiểm thử OTA từ bản106 và chữ ký cài đè.
