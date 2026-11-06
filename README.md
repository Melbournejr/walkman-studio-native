# Walkman Studio Native

Android offline: Kotlin/Compose Material3, SAF→Room→Media3 MediaLibraryService→AudioTrack.
App ID `com.walkmanstudio.nativeplayer`; cài song song với bản WebView cũ. minSdk26,
targetSdk35, compileSdk36. Từ GĐ10, INTERNET/REQUEST_INSTALL_PACKAGES phục vụ OTA được chủ dự án cho phép; không MediaStore hay MANAGE_EXTERNAL_STORAGE;
allowBackup=false. Nhạc được đọc từ URI đã cấp, không copy vào app.

## Đo đường ra USB (GĐ11)

Đạt gate host: 298/298 test, build release/review và lint. Bản107 /
`1.3.1-native-usb`, tag `stage11`; chưa kiểm chứng USB DAC/Xiaomi thật.
Tải: https://github.com/Melbournejr/walkman-studio-native/releases/tag/stage11

Mặc định tắt; Android14+ cần DAC công bố mixer attributes. Đo AudioTrack thực,
format decoder, preferred/supported mixer và routedDevice trong log xuất cục bộ.
Không tự resample; FIR/DSD/EQ giữ nguyên. Quyền normal MODIFY_AUDIO_SETTINGS
được chủ dự án duyệt riêng. Xem [đường ra USB](docs/USB-OUTPUT.md) và
[báo cáo hiện tại](docs/FINAL-REPORT.md) trước khi thử với DAC/ampli.

## Cập nhật trong ứng dụng (GĐ10)

**GĐ10 đạt gate host**: 271/271 test, build release/review và lint qua. Bản 106 /
`1.3.0-native-ota`, cùng chứng chỉ cũ. Nhánh `stage10/in-app-updates`, tag `stage10`.
Tải: https://github.com/Melbournejr/walkman-studio-native/releases/tag/stage10

Bản mới bổ sung kiểm tra/tải APK
qua GitHub Releases trong Cài đặt. Cần cài bản bổ sung OTA một lần; các lần sau app tự
tải trên mạng không tính phí (thường là Wi-Fi), báo sẵn sàng và mở xác nhận cài Android.
Có thể tắt tự động, cho phép mạng tính phí, hủy và thử lại. Không có token GitHub trong app.
APK phải đúng SHA-256, applicationId, versionCode mới hơn và chứng chỉ cũ
`2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2`.
Chi tiết giao thức, quyền mạng và giới hạn kiểm chứng: [docs/OTA.md](docs/OTA.md).
Các trạng thái không có INTERNET bên dưới là lịch sử các phiên bản trước.

## Trạng thái và phạm vi

**GĐ9c đạt** trên `stage9c/cassette-deck`, tag `stage9c`: **220/220 test**,
release/reviewR8vàlintpass;104/1.2.2-native-stage9c. Cassette1,57/phímcơhọc,
Longclockchuẩn, fixtureclockđãsửa cótrace.72 PNGđã mởriêng/hashmanifest,
44cảnhinfo cầncuộn; nềnngoàinhóm đượcghi rõtrongnhậtký, không giấu.
Vinyl48/48 PNGtrùngGĐ9b; core/library/playback/DSP/DSDvàtest cũ không đổi.

Tải: https://walkman-native-releases.silvez.chatgpt.site/stage9c/
APK SHA-256: `66bd2b942c0ccdf6a76932316fca9e22880af6c24515ad96a5989ea20442fb82`.
Cert: `2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2`.
Cùngkhóacũ,v2, code 103→104; chưa cài đè/Xiaomi thực. Xem `docs/FINAL-REPORT.md`,
`docs/stage9c.md`, `docs/stage9c-animation-diagnosis.md`, `docs/stage9c-screens/`.
ThôngtinGĐ9b dưới đây là lịch sử bản trước.

**GĐ9b đạt** trên `stage9b/now-playing-fixes`, tag `stage9b`: **202/202 test**,
0 fail/error/skipped; build release/review có R8 và lint đều pass (0 Fatal/Error,
còn warning). Phiên bản **103 / 1.2.1-native-stage9b**. Không đổi core/library/
playback/DSP/DSD so với stage9. Sửa timeline, tên hiển thị và cassette/vinyl.
96 PNG Roborazzi được mở trực tiếp từng ảnh và xuất vào `docs/stage9b-screens/`.
12 cảnh ngang 200% cần cuộn metadata để xem đủ chip; ghi rõ từng cảnh trong
`docs/stage9b-screens/visual-review.md`. Không suy hiệu năng/âm thanh máy thật từ ảnh.

APK và báo cáo: https://walkman-native-releases.silvez.chatgpt.site/stage9b/
APK SHA-256: `1f7c0c0f4e6b3654127dbbbcdedd6422c1ce815c5154cf1bf4a395ef8126de33`.
Cert SHA-256: `2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2`.
Đúng khóa cài thử hiện tại; APK có chữ ký v2 và versionCode tăng 102→103.
Chưa kiểm cập nhật đè trên Xiaomi. Không tạo khóa mới hoặc đưa private key vào repo.

GĐ1 được chủ dự án nghiệm thu SAF trên Xiaomi14Pro. GĐ2…GĐ9b có commit/tag riêng;
chỉ tag sau gate pass. Xem `docs/FINAL-REPORT.md`, tài liệu từng giai đoạn và
`qa/stageN-gate.json`. Test host không thay cho thử điện thoại. Không có AGENTS.md
trong checkout này; không Ignore, bỏ hoặc hạ assertion để lách gate.

Thư viện incremental size+mtime, WorkManager/coroutine, tag/artwork, disc/track/natural
sort, Paging, folder/album, search, favorites/playlist. Service/session, notification,
focus/noisy, Bluetooth/headset controls, AndroidAuto browse, bounded queue/shuffle,
nhớ vị trí, sleep timer. Guide HyperOS lần đầu có thể bỏ qua; xuất log bằng SAF.
Gapless dùng một ExoPlayer playlist; cần thử file/thiết bị thật để xác nhận timing.

EQ10 biquad±12dB + ClearBass + ReplayGain track/album + headroom. DSP épPCM16,
SourceDirect loại appDSP khỏi chain và cho phép sinkfloat; không hứa bit-perfect.
Đổi direct có thể ngắt ngắn để dựng lại sink, giữ queue/vị trí. Signal view chỉ
khẳng định Format/AudioTrack đã quan sát; không suy tần số DAC hoặc codecBluetooth.
Theme TPS-L2(1979),WM-DD9(1989),MZ-RH1(2006),NW-WM1Z(2016) chỉ ở Đang phát.
Không liên kết hoặc được Sony bảo trợ; tên model dùng để mô tả cảm hứng thiết kế.

GĐ8 (nhánh `stage8/cassette-vinyl`): Cài đặt → Hiển thị Đang phát chọn Cổ điển,
Cassette hoặc Đĩa than; nhớ lựa chọn. Canvas/vector gốc, nhãn đĩa là bìa album tròn.
Vuốt ngang đồ họa để tua; TalkBack có progress/tiến-lùi10giây. Hoạt ảnh chỉ chạy khi
đang phát, không tua, lifecycle STARTED, màn hình bật/không khóa và hệ thống cho phép.
Không tiếng động giả. Chứng cứ render/hiệu năng mô phỏng và giới hạn: `docs/stage8.md`.

Cài đặt → Bù mức DSD0/+3/+6dB (mặc định0), nhân float sau FIR, vẫn áp dụng khi
Source Direct. Không bù PCM thường. Bộ đếm là mẫu-kênh giải mã vượt0dBFS, gồm bộ
đệm/tua lại; reset lúc đổi gain hoặc tạo service. Khối đã đệm có thể giữ gain cũ.
Dither TPDF mặc định bật khi DSP xuấtPCM16, Source Direct bypass. Chỉ xử lý lượng
tử hóa cuối EQ: Media3 vẫn chuyển24-bit/float→16bit trước EQ, không khôi phục bit đó.

| Nguồn | Đường xử lý / giới hạn |
|---|---|
| MP3,FLAC,WAV,AAC/M4A,Ogg Vorbis/Opus | Media3 extractors + MediaCodec; khả năng decoder phụ thuộc máy, chưa nghe trên Xiaomi |
| DSF/DFF DSD không nén mono/stereo64/128/256 họ44.1kHz | Custom Extractor + C++ FIR Kaiserβ8.6→PCMfloat88.2kHz; seek đủ history/lookahead |
| DST,DSD512,SACD ISO,DoP/nativeDSD | Chưa hỗ trợ |
| APE/WavPack/WMA/TTA/ALAC/AIFF và codec lạ | Không có FFmpegNDK extension/container bổ sung; không bảo đảm phát. Một số tag vẫn đọc được |

Bìa nhúng rồi cover.jpg/folder.jpg; disk cache128/512, soft budget96MiB, trim theo
lô, RAM bitmap32MiB. Bìa bị evict được đọc lại qua SAF theo nhu cầu. File đã đổi cần
quét lại; bìa ngoài đổi mà audio stamp không đổi dùng quét toàn bộ thông tin.
Không trần số bài; queue là cửa sổ, không phải nạp100000 MediaItems vào RAM.
Tag batch128; nguồn pipe có thể tua chậm do reopen+skip. Sleep không tồn tại qua
process death; vị trí/selection được lưu riêng, không tự phát khi mở lại.

## Build tái lập

JDK21, Android SDK/platform36/build-tools35.0.0, NDK30.0.16248370, CMake3.22.1.
Cài các package bằng SDKManager/AndroidStudio SDKTools; chấp nhận license của SDK
trên máy bạn. `local.properties` chỉ đặt `sdk.dir`, không commit. Wrapper Gradle8.13.

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'ndk;30.0.16248370' 'cmake;3.22.1'
./gradlew :app:assembleRelease
```

ABI phát hành: arm64-v8a và x86_64; không có32bit. Native ELF và APK được kiểm16KiB
alignment; điều này không thay cho chạy kernel16KiB thật. Release bậtR8, không
có signingConfig riêng của người dùng trong repo.

Dependency version được khóa trong `gradle/libs.versions.toml`: AGP8.13.2,
Kotlin2.2.21,KSP2.2.21-2.0.4,ComposeBOM2025.10.01,Media3 1.11.1,Room2.8.4,
Work2.10.3,Paging3.3.6,Koin4.1.1,coroutines1.10.2,core1.17.0,activity1.11.0,
lifecycle2.9.4. Test JUnit4.13.2,Robolectric4.16.1,AndroidXTestCore1.7.0,Roborazzi1.52.0.
Gradle builtin java-test-fixtures dùng cùng Gradle8.13.

## Gate và kiểm thử

Trên Linux/WSL cài Python3 và g++14 (native reference/JNI host), đặt JAVA_HOME=JDK21:

```sh
python3 qa/gate.py 7
# GĐ8 dùng cùng gate release/review, có thêm test/render mới:
python3 qa/gate.py 8
# GĐ9: cassette dọc/ngang, thêm 14 test và 10 ảnh render
python3 qa/gate.py 9
# GĐ9b: 202 test, Roborazzi 96 cảnh cassette/vinyl
python3 qa/gate.py 9b
# GĐ9c: cassette tỷ lệ thật, phím cơ học, 72 PNG
python3 qa/gate.py 9c
```

Gate7 build release+review, core/library/playback/app unit tests, lint app cả2variant
và library/playbackrelease. `qa/collect-results.py` yêu cầu XML mỗi module,0failed /
0error /0skipped. `:playback:hostNative` build cùng C++ cho Linux, đo3sample/rate và
so float64/sine; không có nhánh test trong production. Native Android cross-compile
arm64/x86_64 và host execution là hai việc khác nhau.

100k benchmark `qa/benchmark-room-100k.json`: streaming fake FileSource + Room WAL,
không gồm SAF/Binder/tag IO. DSD benchmark `qa/dsd-host-benchmark.json`: hostx86_64,
sin tổng hợp, không IO; không suy ra tốc độ Snapdragon. Ngưỡng sin1kHz−12dBFS:
amplitude≤0.1dB,frequency≤0.1Hz,maxabsfloat64≤1e−5FS; không đổi để test qua.

## APK cuối và ký bằng khóa của bạn

`walkman-native-stage9c.apk` là build **review**, cùng cấu hình release/R8,
debuggable=false, ký bằng đúng khóa cài thử đã dùng cho các bản bàn giao trước.
Đây **không phải khóa release riêng của bạn**. VersionCode104, versionName
`1.2.2-native-stage9c`. `assembleRelease` cũng tạo APK chưa ký ở
`app/build/outputs/apk/release/`; không chứa signingConfig riêng của người dùng.

```sh
python3 qa/check-final-apk.py walkman-native-stage9c.apk --stage 9c --previous-apk walkman-native-stage9b.apk
apksigner verify --verbose --print-certs walkman-native-stage9c.apk
sha256sum walkman-native-stage9c.apk
```

Checker yêu cầu đúng APK GĐ9b đã giao, code 103→104, cert cố định ở trên và v2,
không INTERNET/quyền rộng, ABI/JNI/R8/16KiB alignment. PEM công khai đi kèm chỉ
xác định người ký, không chứa private key, không thể dùng để ký và không cần cài.
Để cập nhật đè, cài APK bằng đúng khóa hiện tại; không ký lại bằng khóa khác.
Nếu bản trên máy bạn đã tự ký bằng khóa riêng, phải dùng lại khóa riêng đó.
Chưa thực hiện cập nhật trên máy thật.

Bạn có thể ký lại APK đã tải bằng keystore sẵn có của bạn. Để BuildTools35.0.0
trong PATH. Chạy ở thư mục riêng, không đặt keystore trong checkout:

```sh
zipalign -P 16 -f -v 4 walkman-native-stage9c.apk walkman-native-aligned.apk
apksigner sign --ks /duong-dan-rieng/khoa-cua-ban.jks --ks-key-alias TEN_ALIAS --out walkman-native-own.apk walkman-native-aligned.apk
apksigner verify --verbose --print-certs walkman-native-own.apk
sha256sum walkman-native-own.apk
```

Apksigner hỏi mật khẩu tương tác; không viết mật khẩu vào lệnh, Gradle hoặc file
trong repo. Hash tải về áp dụng trước khi bạn ký lại; ký làm đổihash. Bản khác khóa
không cập nhật đè nhau: nếu cần gỡ bản cài thử để đổi sang khóa riêng, dữ liệu app
và URI grant sẽ mất, phải chọn lại thư mục. Tệp nhạc gốc vẫn giữ nguyên. Các bản
sau dùng nhất quán khóa riêng và tăngversionCode. Không có lệnh tạo khóa trong repo.

## Máy thật và log

`docs/DSD-DEVICE-TESTS.md` là danh sách đã đưa trướcGĐ6. Trên Xiaomi14Pro/Android16:
đọc Help trong Cài đặt, đặt pinKhônghạnchế/Tựkhởiđộng/khóađa nhiệm theo UI máy.
Thử gapless, seek, DSP/direct, ReplayGain hai bài, notification/khóa màn hình,
headset/BT, cuộc gọi/rút tai nghe, tắt màn hình/task swipe/kill/restore; thử font 200%,
TalkBack, xoay, theme và thư viện lớn. Ghi lại màn hình Help đã tự xác nhận.

Xuất: **Cài đặt → Xuất log trình phát → chọn đích SAF**. Sau kill, mở lại rồi xuất
để có ApplicationExitInfo khi Android cung cấp. dsd_stats chốt khi dừng/chuyển
source/release: frames,FIRms,windowms,RTF; kèm model/SDK/ABI. Không log tức thời nếu
hệ thống kill không callback. Không upload tự động. Android16 sources ở
`docs/android16-background-research.md`; không suy mọi cài đặt HyperOS từ API Android.

## Giấy phép

GPL-3.0-or-later vì tái sử dụng DSD GPL bản cũ. Giữ LICENSE, copyright, NOTICE và
nguồn tương ứng khi chia sẻ APK; source snapshot và nguồn trích trong
`third_party/legacy-dsd/NOTICE.md`. Thư viện Apache2/LLVM giữ notice trong assets.
Không dùng FFmpeg ở bản này. Source archive/bundle đi cùng link tải để giữ các tag.

SauGĐ7: crossfade, đo/tối ưu Snapdragon bằng máy thật, lặp toàn bộ thư viện,
codec/container mở rộng nếu cần, artwork1024px, và USB/exclusive chỉ khi có thiết bị
và có bằng chứng đường ra. SAF tiếp tục là nguồn duy nhất; không tự bật quyền rộng.
