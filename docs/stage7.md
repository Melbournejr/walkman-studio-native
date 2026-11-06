# GĐ7 — Theme, hiệu năng và đóng gói

Tạo WalkmanThemes, sửa NowPlaying/Settings/SavedState/system bars để theme chỉ ở
Đang phát và đọc được trên chrome sáng/tối. Giữ danh sách16/13sp,row72dp,tap48dp.
Thêm contrast tests cho4palette, display và gradient endpoints; assertion≥4.5
của2palette cũ giữ nguyên. Không ảnh demo/logo Sony hoặc đồ họa máy chiếm list.

Sửa cache lỗi thật: trước đây evict ảnh nhưng metadataVersion vẫn hoàn tất nên
không có đường đọc lại. ArtworkResolver đọc tag/folderart qua FileSource SAF theo
nhu cầu, serialize/coalesce, giữ nguyên tag/ID; coverKey index và migration3→4.
Không alias hash khi nguồn đã đổi, không xóa bài khi hết grant. Bitmap32MiB,
tránh digest/draw allocation lặp; xóa giá trị cũ khi đổi coverkey. Trim theo10s
hoặc8MiB dữ liệu mới (soft96MiB→80MiB); cache tạm có thể vượt budget≤một lô.
Tag batch32→128 giảm số lần invalidation/transaction với100k bài. Cache event
refresh metadata bìa của session mà không đổi URI/nguồn phát. Export log có snackbar.

Bổ sung MediaButtonReceiver chuẩn củaMedia3: thiếu đăng ký receiver là thiếu đường
cold resumption khi service không chạy, không phải lỗiRobolectric. Callback
onPlaybackResumption đã có; fixture kiểm mergedmanifest. Đã đối chiếu Javadoc
MediaButtonReceiver1.11.1, không thay code production để né test. Phải thử headset
sau processdeath trên máy thật; ForceStop vẫn chịu chính sáchAndroid.

README có version/build/test/formats/limits, tự ký bằng khóa sẵn có, không tạo khóa
trong repo. version1.0.0-native(100), min26/target35. Gate7 thêm release+review/R8,
lint cả2variant. APK cuối lấyreview: debuggable=false, ký khóa cài thử hiện có,
chưa phải khóa người dùng. Release unsigned vẫn là build output để tự ký.

## Test và nghiệm thu
`python qa/gate.py 7` rồi `python qa/check-final-apk.py ...` để kiểm certificate,
package/SDK/version/permissions, ABI/JNI/R8 và16KiB ELF/ZIP. Chỉ commit/tagstage7 khi
mọi gate trả0. Test mới cover evict/coalesce, changedhash, permissionloss, folderart
không rõsize và migrations; tất cả assertion/test cũ được giữ. Benchmark100k và
DSD source/host/giới hạn nằm trong JSON và docs tương ứng.

## Not verified
Không có Xiaomi14Pro hoặc máy Android thật/emulator trong môi trường này. Chưa
nghe MP3/FLAC/AAC/DSD, đo gapless/seek latency/DSP audio, đoSnapdragon<0.25x,
Doze/HyperOS/BT/headset/Auto/focus/noisy/kill, TalkBack/font200%/xoay/theme trên máy.
Chưa xác nhận kernel16KiB thật, DAC/codecBluetooth, hoặc ký bằng khóa riêng của bạn.
Các gate host/crosscompile không được ghi là những việc trên đã hoạt động.

## Cách tôi tự kiểm trên máy thật
Cài APK, chọn lạiSAF nếu cần; thử4theme, xoay, TalkBack/font200%. Cuộn lại album
cũ sau khi Android dọncache: bìa phải đọc lại, không làm đổi tag. Kiểm playlist/
favorite sau quét và khởi động lại. Làm checklist docs/DSD-DEVICE-TESTS.md và mục
Máy thật trongREADME, xuất log, gửi kèm model/OS/đầu ra/thời điểm/thao tác. Tự ký
bằng keystore hiện có theoREADME trước khi dùng khóa riêng lâu dài.

## Gate đã chạy
Release+review/R8,123/123 tests,lint release/review +library/playbackrelease đều0.
APK V2 signature,manifest/SDK/permissions,JNI trongDEX+ELF,ABI và16KiB ZIP/ELF
đã kiểm bằng qa/check-final-apk.py. Unchanged native methods không xuất hiện
trong R8 mapping nên kiểm trực tiếp DEX declarations và ELF exports.
Xem qa/stage7-gate.json,stage7-test-results.json,final-apk.json.
