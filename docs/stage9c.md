# GĐ9c — Cassette tỷ lệ thật và phím cơ học

Nhánhstage9c/cassette-deck từstage9b, tag stage9c;104/1.2.2-native-stage9c.
Fullgate cuối220/220,release/reviewR8vàlintpass,72 PNGđã mởriêng từng ảnh.
Xem [FINAL-REPORT.md](FINAL-REPORT.md), [chẩnđoán](stage9c-animation-diagnosis.md),
[nhậtký72 PNG](stage9c-screens/visual-review.md).

## Files tạo/sửa

- CassetteDeck.kt: housing/tapevector3Dgiả, hub/vít/băng/giấy/kính/light.
- CassetteDeckScreen.kt: NaturalPortraitDeckLayout, CassetteDeckGeometry1,57,
  CassetteDeckPosition Longlive/Doublepreview, bố cục gọn/metadata/seek.
- MechanicalTransport.kt:3phím/latched/momentary/haptic/semantics/tween/spring.
- CassetteDeckMotion.kt: coast 150ms hữu hạn, lifecycle/reducedmotiongate.
- CassettePlayerScreen.kt: nhánhCassette mới; vinyl giữshell cũ.
- PlaybackViewModel.kt/PlaybackPanel.kt: chỉ truyềnraw playbackState vàoUIsnapshot.
- CassetteMechanicalStateTest.kt/Stage9cScreenshotTest.kt:18test mới/72 PNG;
  fixtureSnapshot.sendApplyNotifications, không đổi160ms/1±0,01.
- app/build.gradle.kts vàqa/gate.py/collect-results.py/check-final-apk.py:
  version104,stage9c,certPINvàthamchiếuAPK9bcode 103.
- README/FINAL-REPORT/QAJSON/log/XML vàstage9c-screens: bằngchứng/lịchsử.

## Build và nghiệm thu

./gradlew :app:compileDebugUnitTestKotlin trướcgate đầy đủ sau sửa test.
python3 qa/gate.py 9c:220/220(52/44/35/89),0fail/error/skip,lint0Fatal/Error.
Release/reviewR8,hostnative,app2variantlint,library/playbacklint đều trong gate.
JDK21/Gradle8.13/SDK36/BuildTools35/NDK30.0.16248370/CMake3.22.1 nhưREADME.
Source/audio/test cũ không đổi; chỉthayassertwidth55%conflict đã được người dùng cho phép
bằngratio1,5–1,65; deck80%/GĐ9b68%/touch64-56/safebounds giữnguyên.

## Quan sát

72 PNG mở thật;44cảnhinfo có phầnngoàiviewportcuộn,18cảnhellipsisnguyên từ.
Nềnngoàinhóm còn dọc50–150dp,ngang180–195dp do tỷlệthật; đãliệtkêtừngfile,
không gọi mọithôngsố cùng hiểnthị hoặc mọi ảnh không có nềntrống.
Ảnh trước sửa/lỗi fixture cũ được giữriêngtrongqa/stage9c-review/.
48/48 PNGvinyl trùng byteGĐ9b. Hostcapturemean21,450ms không suyFPS/pinphone.

## Not verified

Chưa thử Xiaomi/HyperOS,installupdate/audio/haptic/TalkBack/fontOEM/rotation,
BT/notification/pin/FPS/kernel16KiB. Render tĩnh không chứng minh đườngphát.

## Cách tôi tự kiểm trên máy thật

Theo6bước FINAL-REPORT: cài đèđúngkhóa;2hướng/font 200/4theme; keyfromnotification/
headset; clock/seek/restore; lifecycle/HyperOS; xuấtlogSAF khi lỗi.
