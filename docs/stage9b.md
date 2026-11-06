# GĐ9b — dữ liệu và giao diện cassette/vinyl

Nhánh `stage9b/now-playing-fixes`, nền `stage9`, tag `stage9b`. Version103 /
`1.2.1-native-stage9b`. Gate202/202, build/test/lint/release/review đạt trên host;
không thay core/library/playback/DSP/DSD. Hash/cert/giới hạn: [FINAL-REPORT.md](FINAL-REPORT.md).

## File tạo/sửa

- PlaybackPresentation.kt: timelineKnown độc lập seekEnabled, display-only cleanup.
  Giữ ngoặc, chỉ bỏ whitelist khớp cặp; không đoán ngoặc lệch.
- PlaybackViewModel.kt/PlaybackPanel.kt: Room duration đúng ID, không stale position/
  telemetryidle; state UI không tạo player mới.
- CassetteSignal.kt: chip nguồn/ngõ ra đã quan sát, ẩn AAC bitrate chưa xác thực.
- WordFitLabel.kt: tối đa2dòng, auto-fit nguyên từ/ellipsis, full title TalkBack.
- CassetteDeck.kt/NowPlayingVisuals.kt: hub/băng/kính và kim vinyl.
- CassettePlayerScreen.kt/SoundControls.kt/2drawablequeue-audio: safearea, một title/
  tim, controls rõ chức năng, cue cuộn ngang, nhóm artwork/metadata/seek, font200.
- app/build.gradle.kts/catalog: version103, Roborazzi1.52.0.
- Test mới PlaybackPresentation/FilenameCleanupRegression/WordFitLabel/
  DisplayTransportGeometry/Stage9bScreenshot; cập nhật chip/render tests cho UI mới.
- qa/gate.py/collect-results.py/check-final-apk.py: stage9b và signer/version checks.
- README/FINAL-REPORT/QA JSON/stage9b-screens: lịch sử và96PNG gốc đã duyệt.

## Build/test và nghiệm thu

```sh
./gradlew :app:compileDebugUnitTestKotlin
python3 qa/gate.py 9b
python3 qa/check-final-apk.py walkman-native-stage9b.apk --stage 9b --previous-apk walkman-native-stage9.apk
```

JDK21/Gradle8.13/SDK36/BuildTools35/NDK30.0.16248370/CMake3.22.1, dependency cụ thể
trong catalog. Helper cloud-gradle chỉ cấp đường dẫn/proxy/CA cho môi trường.
Gate gồm hostnative,release/reviewR8,4moduletests,app2variantlint,library/playbacklint.

202test(52/44/35/71),0fail/error/skip,lint0Fatal/Error. Giữ artwork>=68% ngang360dp,
touch>=48dp; idle/restore/unknown/overshoot/nonseekable có test riêng. Thêm8test
filename không sửa test cũ và3testlayout sau review; không Ignore/xóa/hạassertion.

96PNG đã mở thật từng ảnh, hash khớp: [visual-review.md](stage9b-screens/visual-review.md).
12cảnh ngangfont200ready có chip bị che trong viewport cuộn ngang, phải vuốt.
Cassette dọc100% còn khoảng60–90dp quanh nhóm transport/utility, art/meta/seek liền.
Cảnh lỗi trước sửa và lịch sử gate giữ trong qa/stage9b-review/before-layout-fix/.

## Not verified

Xiaomi14Pro/Android16HyperOS,cài đè/nghe khi xoay,TalkBack/fontOEM,hoạt ảnh động,
Bluetooth,CPU/FPS/pin,fileAACthật,kernel16KiB. Renderhost tĩnh không kiểm đường phát.
Không hứa bit-perfect hoặc tốc độ DSD trên Snapdragon.

## Cách tôi tự kiểm trên máy thật

Theo6bước trong FINAL-REPORT: cài đè đúng khóa,2mode/dọcngang/font200,queueidle/
nonseekable/seek,chip/tênfile,lifecycle/HyperOS và xuất logSAF nếu lỗi.
