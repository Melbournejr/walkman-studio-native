# FINAL-REPORT — GĐ9c đạt gate và đã duyệt72 PNG

Nhánh `stage9c/cassette-deck`, nền tag `stage9b`, tag bàn giao `stage9c`.
Version **104 / 1.2.2-native-stage9c**, app ID `com.walkmanstudio.nativeplayer`.
Chốt báo cáo05/11/2026. GĐ1 do người dùng nghiệm thu SAF; giữ các tag đã bàn giao
stage2…stage9b. GĐ9c đạt kiểm chứng host; chưa kiểm trên Xiaomi thật.

## Kết quả

| Kiểm tra | Kết quả |
|---|---|
| Compile unit-test ngay sau sửa test | PASS; sau sửa visual cũng compile lại PASS1m24s |
| Fullgate9c cuối | PASS, exit0,1048,011giây |
| Core/library/playback/app tests | 52/44/35/89 = **220/220**,0fail/error/skipped |
| Release/review R8 + lint | PASS;0Fatal/Error, warningapp18 mỗi variant/library3/playback9 |
| PNG | **72/72 mở riêng từng ảnh**, hash khớp manifest; exported docs/stage9c-screens |
| Vinyl regression | 48/48 PNG trùng byte với bảnGĐ9b; không đổi thiết kế |
| APK audit | Version/certV2/noINTERNET/quyềnrộng/JNI/R8/ABI/16KiBalignment PASS |

Thêm18test so vớiGĐ9b; riêng lượt tiếp tục thêm5testLong và1UItest hai đồng hồ.
Không Ignore/xóa/hạ assertion để lách. Test cũ củaGĐ9b không đổi byte. Người dùng
đã cho phép thay ngưỡng chiều rộngcassette55% bị xung đột bằng ratio1,5–1,65;
ngưỡngdeck80%, GĐ9b68%, vùngchạm64/56 và safe/unclippedbounds vẫn giữ nguyên.

## Chẩn đoán trước sửa: nguyên nhân(b), fixture snapshot/clock

Không đổi production MechanicalTransport/tween/spring để test qua, không tăng
160ms hoặc thay1,0±0,01. @Before vẫn scale0 cho ảnh tĩnh. Trace ở scale0 cho thấy
inputrequestedtrue,rawREADY=3 đi vào latch/target1 tại32ms; animation tăng từ0
qua0,077/0,422/0,749/0,905/0,975/0,999 đến1 tại160ms khi đồng bộ từngframe.
Vì vậy scale0 không chặn animation trong test composable trực tiếp; state đã vào
phím. Run đối chứng thêm Snapshot.sendApplyNotifications trước MỘT advance160ms
cho value1 tại160, pause-reduced value0 ởframe kế, play-reduced value1 ởframe kế.

Sửa đúng fixture: publish snapshot trong runOnIdle trước advance clock; không
sửa productionanimation hoặc thêm thời gian chờ. Log/XML của runtrace còn ghi
assertpause chưa reach trongtestgốc bịstale, cùng nguyên nhân; được giữ nguyên,
không che. [Chẩn đoán đầy đủ](stage9c-animation-diagnosis.md), bảng0…160ms và
QA JSON/XML/log ởqa/stage9c-review/animation-diagnostics/.

## Sửa production thực tế

- CassetteDeckPosition.display giữ Long đã clamp khi không kéo/preview; chỉ đổi
  progress→ms bằngDouble khi preview thật. Regression1000/180001ms hiện0:01 trên
  cảelapsedvàcounter; endpoint,0,unknown/restore,negative/overshoot vàLong lớn pass.
  Đây là lỗi hiển thị thật: code cũ vòng quaFloat thành999ms, không phải lỗi test.
- CassetteCanvas đo thật1,57:1, căn giữa vùng fit, deck tính kích thước tự nhiên
  từ cassette+phím. Không giãn tape theo slot. Giữ phím trước/sau>=56dp,Play>=64dp.
- Deck/cassette vector bevel/giấy/kính/đầu từ/capstan/pinchroller/vít; nguồn sáng
  trên-trái, bóng xuống-phải; hub vòng trắng tròn ngoài/sáu răng hướng vào tâm,
  lỗ trục tối mở. Không bitmaptexture, blurAPI31, thư viện3Dmới, logo hoặc âmthanhgiả.
- Play latch theo playWhenReady+rawPlayerState từ controller; idle/ended nhả.
  Previous/next momentary, nhấn 100ms/nhảspring≈142ms, chìm 4dp; hapticCLOCK_TICK.
  Hoạt ảnh/coast 150ms hữu hạn; ẩn/tua/giảm hoạt ảnh dừng, không catch-up nền.
- Giữ sửa9b: title một lần/ngắt nguyên từ, tag/filename/URLcleanup, ẩn artist trống,
  Room duration/idle/nonseekable, Ngõra, AACbitrate chưa xác thực ẩn, mộtfavorite.
- Không sửa core/library/playback/DSP/DSD, callback phát hoặc tạo player mới.
  AppViewModel/Panel chỉ bổ sung raw playbackState vào snapshot hiển thị.
  Không INTERNET, MediaStore hoặc quyền mọi tệp; SAF giữ nguyên.

## Duyệt ảnh thật và những gì vẫn nhìn thấy

Ma trận72: cassette×dọc 400×800/ngang 900×360×playing/paused/idleunknown×
tênngắn/dàiViệt/raw×PCM/DSD×font 100/200%. RobolectricAPI35/nativeSkia/mdpi,
safeinsets24/16dp, dữ liệu fixture, khung hình giảm hoạt ảnh. Có kiểm bounds
360×640font 100 không cần cuộn. Không thay ảnh thật bằng contact sheet/ảnh chỉnh.

Gate đầu220pass nhưng ảnh thật phát hiện gap giữa controls, header sát viền,
hub vùng âm dễ gợi hình sao. Đã gom tất cả controls thành nhóm liền nhau, cột
info gọn, insetheader12dp, tăng vònghub/giảm độ nhọn vùngâm; compile vàgate đầy đủ
chạy lại rồi mở đủ72ảnh cuối. Lịch sử3PNG minh họa vàgate cũ ởbefore-visual-fix/.

Lượt cuối không thấy cắt dọc/thời gian, chồng chữ, ngắt giữa từ, sai phím/lights
hoặc ratio méo. **44cảnh** cóartist/chip bị che tại viewport ngang và cần vuốt;
**18cảnh** dùng ellipsis theo nguyên từ. **Mọi ảnh còn nền ngoài nhóm**: dọc
khoảng50–150dp trên/dưới tùyfont/trạngthái; ngang khoảng180–195dp hai bên. Đây là
phần nền khi ưu tiên tỷ lệ thật và deck tự nhiên; không tuyên bố hết mọi khoảng
trống hoặc mọi thông số cùng nhìn thấy. Gap lớn GIỮA các nhóm đã gom lại.

Từngfilename/quan sát/hash: [visual-review.md](stage9c-screens/visual-review.md),
visual-review.json vàscreenshots.json. Hub cóvòngngoàitròn, răng nằm trong lỗ trục;
không dùngiconbánhrăng. Nhãnngangfont 200 dài có thể chỉ1dòng vớiellipsis, fulltitle
vẫn trongsemantics/TalkBack. Không thêm titletrùng để lấp nền trống.

Capture72frame tĩnh bằngSystem.nanoTime gồm đồngbộ+Bitmapallocation+View.draw:
mean21,450ms,p50=19,793ms,p95=40,063ms trên host. Không phảiCPU%,GPUframetime,
FPS/pin trên điện thoại hoặc benchmark hoạt ảnh liên tục.

## APK, chữ ký và nguồn

- **walkman-native-stage9c.apk**,4.091.285 byte,review dùngrelease/R8,
  debuggable=false,minSdk 26 / targetSdk 35,arm64-v8a/x86_64.
- SHA-256: `66bd2b942c0ccdf6a76932316fca9e22880af6c24515ad96a5989ea20442fb82`.
- Cert SHA-256: `2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2`.
  Apksigner xác minhV2, đúng khóa cài thử hiện tại vàAPK9b chuẩn, code 103→104.
  Không tạo khóa khác. Chưa ký bằngkeystore release riêng của người dùng.
  APK đã nhúng chữ ký; PEM công khai không chứa privatekey/không dùng để ký.
- Tải APK/ảnh/báo cáo/source: https://walkman-native-releases.silvez.chatgpt.site/stage9c/
- GPL-3.0-or-later, giữLICENSE/NOTICE/nguồn DSD cũ; sourceZIP vàgitbundle đủ
  tag stage2…stage9c cùng APK. Không cóprivatekey hoặcbuildcache trong source.

## Not verified

Chưa cài/cập nhật/nghe trên Xiaomi14Pro/Android16HyperOS; chưa kiểmfontOEM,
TalkBack/haptic thực, hoạt ảnh động/pausecoast, xoay/notification/headset/BT/
audiofocus/gapless,CPU/GPU/FPS/pin hoặc kernel16KiB. Ảnh/clocktest trên host và dữ
liệu fixture không chứng minh decodeDSD/DSP/đường ra âm thanh. Không hứa bit-perfect,
tần số DAC/codecBluetooth hoặc tốc độ Snapdragon từ các số đo host.

## Cách tôi tự kiểm trên máy thật

1. Cài đè không gỡ app: adb install -r walkman-native-stage9c.apk; kiểm version104,
   cùng cert, SAF/thư viện/playlist/yêu thích còn nguyên.
2. Cassette dọc/ngang, chữ100/200%, tên Việt dài/raw/PCM/DSD/thiếutag; cassette
   đúng tỷ lệ, phím trong case, title/tim mộtlần, thời gian đủ. Vuốt info để xem chip còn lại.
3. Phát/pause từ app/notification/headset/BT: phím chìm/nổi đúng; thử buffering,
   idle/ended và phát lại; trước/sau momentary, haptic nhẹ, TalkBack nhãn đúng.
4. Thử vị trí 1 giây, cuối bài,tua/restore/unknown/nonseekable; kiểmhai đồng hồkhông
   tụt giây. Pause/seek/nền/khóa/giảm hoạt ảnh: hình dừng đúng, nhạc vẫn theo service.
5. So vinyl với9b và thử 4 theme, Hàngđợi/EQ/âm lượng. KiểmHyperOS pinKhônghạnchế,
   Tựkhởiđộng/khóađa nhiệm theoHelp; ghi lạiđầu ra loa/tai nghe/BT.
6. Lỗi: Cài đặt→Xuất logtrìnhphát→SAF; gửi model/OS, đầu ra,thời điểm,thao tác/file.
