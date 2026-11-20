# GĐ11 — Đường ra USB DAC

**Đạt gate host**: 298/298 test, không lỗi/skip; build release/review và lint app/library/playback qua. Gate cuối: 1144.259 giây. 27 test mới so với stage10: core19, playback4, app4.

Bản **107 / 1.3.1-native-usb**, nhánh `stage11/usb-output`, tag `stage11`. APK dùng cấu hình release/R8, cùng chứng chỉ đang cài; không tạo khoá mới.

- [APK](https://github.com/Melbournejr/walkman-studio-native/releases/download/stage11/walkman-native-stage11.apk)
- [OTA update.json](https://github.com/Melbournejr/walkman-studio-native/releases/download/stage11/update.json)
- [Release và mã nguồn GPL](https://github.com/Melbournejr/walkman-studio-native/releases/tag/stage11)
- SHA-256 APK: `0771ab03c70cc6b63544008933fda3079cfc04514769192bc10140b1e97423da`
- SHA-256 chứng chỉ: `2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2` — apksigner xác nhận trùng stage10, versionCode tăng đúng 1.

Đã thêm log `output_route` (AudioTrack thực, routedDevice, capabilities, mixer supported/preferred, API) và `decoder_output` (PCM nhận từ decoder, bit depth nguồn nếu biết). Bộ chọn mixer thuần Kotlin không tự resample. Công tắc mặc định tắt; tên trung tính trước khi thiết bị xác nhận, có cảnh báo âm lượng DAC lần đầu. Xác nhận cần mixer đọc lại đúng, AudioTrack đúng rate/encoding/channels, routed USB, chuỗi Source Direct thực, gain DSD0 và âm lượng số1. Trạng thái cũ bị huỷ khi controller ngắt; AudioTrack tạo từ chuỗi DSP cũ không đủ điều kiện dù công tắc Source Direct vừa bật.

Cấu hình cần đổi encoding (24→32 hoặc DSD float→int) được báo rõ; GĐ11 giữ mixer hệ thống, không thêm bộ chuyển đổi mẫu. Nếu biết nguồn >16 bit nhưng decoder trả PCM16 thì không xác nhận. Giữ nguyên FIR/libwalkman_dsd, DSD, EQ và dither; thư viện native trong APK được đối chiếu byte với stage10. Chỉ thêm quyền normal `MODIFY_AUDIO_SETTINGS` theo ngoại lệ người dùng đã chấp thuận; quyền OTA giữ nguyên.

Lỗi test log tại dòng49/67 trước đó: file không tồn tại trong thư mục fixture sau flush. Đã giữ toàn bộ assertion, tạo logger riêng cho từng Application Robolectric, kiểm tra thư mục và đóng writer trước teardown; không thêm guard production để chữa test. Trace mới có `retainedFile=null`, nên giả thuyết logger giữ Context cũ **chưa được chứng minh**. Bốn test telemetry hiện qua; [chẩn đoán](../qa/stage11-log-fixture-diagnostic.json), [gate](../qa/stage11-gate.json), [test](../qa/stage11-test-results.json), [APK](../qa/stage11-apk.json), [native/quyền](../qa/stage11-frozen-audio.json).

## Not verified

Chưa chạy Xiaomi14 Pro/Android16 hoặc USB DAC thật. Host/Robolectric không xác minh AudioManager/OEM, listener/rút nóng, dữ liệu USB/tín hiệu điện, âm lượng DAC, FLAC24 trên decoder máy, đổi rate khi gapless, chạy nền/audio focus với DAC, cài đè và OTA trên điện thoại. Mã dọn mixer có test fake; dọn khi hệ thống kill tiến trình không thể dựa vào onDestroy luôn được gọi. Không suy kết quả trên điện thoại từ host.

## Cách tôi tự kiểm trên máy thật

1. Từ bản106: Cài đặt → Cập nhật → Kiểm tra bản mới, hoặc cài APK107 đè. Hạ âm lượng DAC/ampli trước; chọn USB làm đầu ra. Bật Source Direct, bù mức DSD0 rồi bật mixer USB và xác nhận cảnh báo.
2. Phát WAV16/44,1; FLAC24/96; FLAC24/192; DSD→PCM88,2 float. Xuất log trình phát; so sourceBitDepth với decoder pcmEncoding, AudioTrack, routedDevice, supported/preferred mixer. Không biết phải hiện thiếu dữ liệu, không suy số từ tên file.
3. Thử tắt công tắc, EQ, DSD+3/+6, chuyển Bluetooth, rút DAC, pause/dừng service và mở lại app. Trạng thái phải mất xác nhận khi thiếu điều kiện; log ghi đường ra và lỗi API nếu có. Sau bù DSD, đưa về0 và khởi tạo phiên phát mới để loại bộ đệm còn gain.
4. Lưu `adb shell dumpsys audio > audio.txt` và `adb shell dumpsys media.audio_flinger > audio_flinger.txt` lúc phát. Đối chiếu device/output rate/format với log. Nếu DAC có đèn/màn rate, chụp khi đổi 44,1→96→192→88,2kHz, tra hướng dẫn đúng DAC; đèn rate không chứng minh mẫu dữ liệu nguyên vẹn.
5. Thử DAC không công bố mixer, loa/Bluetooth, Android<34: có lý do không khả dụng, giữ đường phát cũ, không crash. Kiểm tra âm lượng/focus, màn khoá và phát nền trên HyperOS.

API và giới hạn: [USB-OUTPUT.md](USB-OUTPUT.md). Workflow phát hành đặt tại `.github/workflows/publish-native-stage11.yml` trên nhánh main của repo phát hành; ghép APK giữ nguyên byte/chữ ký, xác minh hash, đưa source lên native rồi công bố OTA.
