# GĐ9 — Cassette theo bố cục dọc/ngang

Nhánh `stage9/cassette-ui`, từ `stage8`; tag nghiệm thu `stage9`.
Gate build/test/lint/release/review đã đạt: **162/162 test**, không fail/error/skip.
versionCode 102 tăng từ 101; APK `walkman-native-stage9.apk` đã được apksigner
đối chiếu cùng chứng chỉ với đúng `walkman-native-final.apk` GĐ7 đã giao.

## Phạm vi và code

Chỉ appUI/presentation và QA/docs. Không sửa core/library/playback, renderer,
DSP hoặc DSD decoder. Danh sách file ở `stage9-files.txt`, toàn bộ code trong repo.
NowPlayingVisual giữ frameclock/lifecycle/screen/reduced-motion và cơ chế tua GĐ8;
chỉ thay artwork Cassette bằng Canvas gốc, tách CassetteDeck. Không bitmap, logo,
tên hãng hoặc tiếng động giả. Bốn theme là màu, menu Cassette dùng Xanh/Đen/Bạc/Vàng.

Nhãn Unicode tối đa2dòng bằng TextAutoSize14…20sp, không ghi đè fontScale; nếu vẫn
dài thì ellipsis. Sáu răng thực mỗi bánh, quay CCW; diện tích cuộn theo tiến độ
giữ công thức GĐ8. Cửa sổ phản chiếu nhẹ, trục/con lăn ở dưới, thân tối trong mờ.

CassettePlayerScreen thuần state/callback, ratio1.6, thumb controls trái/phải ở
ngang, dọc xếp metadata/chips/tua/controls. Nút phát64dp, trước/sau56dp; status
48dp. Tên đầy đủ marquee khi phát và hiển thị; dừng khi ẩn/tua/reduced motion.
Cuộn ở font200%; deck/title theo bounds clip để ngừng frame khi ra khỏi viewport.
Hàng đợi hiển thị chỉ số cửa sổ Media3 thực, không giả tổng100k bài; shuffle cũng
áp dụng hàng đợi đã nạp qua APIcontroller hiện có. Favorite lưu Room như trước.

Cassette luôn ngang lưu preferences; MainActivity chỉ yêu cầu SENSOR_LANDSCAPE
khi NowPlaying+Cassette+option, ngoài ra UNSPECIFIED. Không đưa player vào Activity
hoặc giữ orientation bằng configChanges. Smoke test giữ instance player thuộc
service qua Activity.recreate; không thay cho đo âm thanh liên tục trên điện thoại.
Âm lượng mở panel Android29+ hoặc SoundSettings26; EQ/queue mở sheet đã có.

## Thông số thật

CassetteSignal lấy Format audio đang selected qua MediaController: MIME/rate/
pcmEncoding/averageBitrate; peak chỉ hiển thị với ≤. Không suy depth từ codec lossy,
không suy DSD từ filename/source string cũ, không nhãn chất lượng. DSD chỉ nhận
extractor id `dsd-pcm` và label do header cung cấp64/128/256,
audio/raw88200Hz +encoding đã quan sát. Ví dụ DSD128 → PCM88,2kHz·float.
AudioTrackchip chỉ dùng session telemetry định dạng chuẩn, không gọi đó là DAC.
Thiếu thông số thì ẩn; không đổi service hoặc bộ giải mã để thêm chip.

## Test/build và nghiệm thu

`python3 qa/gate.py 9`: release/review/R8, tests4module, lint app release/review
và library/playbackrelease. Không Ignore/bỏ test/hạ assertion.
`python3 qa/check-final-apk.py APK --stage 9 --previous-apk walkman-native-final.apk`:
kiểm file tham chiếu SHA, appID/version102, signer V2 bằng apksigner, JNI/16KiB,
không INTERNET/broad storage; không khớp signer thì dừng.

Render test native Skia/Robolectric:8tổ hợp dọc/ngang×title ngắn/dài tiếng Việt
×DSD/PCM; thêm2ảnh font200. Kiểm pixel/dimensions/semantics/callback/tua, touch56/64,
controls scroll tới được. Metadata của scene là fixture, không phải file DSD thật.
Có 14 test mới so với GĐ8: core 52, library 44, playback 35, app 31.
Giữ testGĐ8 quay/dừng/lifecycle và DSP/DSD nguyên trạng; thêm test hướng quay,
chip thiếu/sai dữ liệu, preferences và chính sách orientation.

Ảnh nguồn ở `qa/stage9-rendered/`, manifest có kích thước/hash cho từng ảnh.
Render thật đã phát hiện icon kế thừa màu đen trên nền tối; component được sửa
để tự cung cấp theme ink, vì MaterialTheme không ghi đè LocalContentColor của host.
Đây là lỗi tương phản UI thật; test thêm assertion pixel, không sửa để né Robolectric.

Benchmark thành phần GĐ8 chạy lại với CassetteFace GĐ9, 60 khung/chế độ:
Cassette trung bình 56,68 ms, p95 99,29 ms; đĩa than 31,66 ms, p95 68,36 ms.
System.nanoTime quanh đồng bộ Compose + cấp bitmap + View.draw/Skia, không gồm
hash/PNG; tiến độ điều khiển, quay tĩnh do giảm hoạt ảnh. Đây là máy chủ Robolectric,
không phải benchmark toàn màn Cassette mới hoặc hoạt ảnh phát liên tục.
Số liệu và mẫu thô: `qa/stage9-ui-benchmark.json`; không đo CPU%.

## Not verified

Chưa cài APK hoặc thử âm thanh/cập nhật đè trên Xiaomi14Pro/Android16HyperOS.
Chưa đo CPU/GPU/pin/FPS, âm thanh khi xoay, ngẫu nhiên/âm lượng/panel OEM,
TalkBack thực, font200% trong mọi ngôn ngữ, đa cửa sổ/Android16 có thể bỏ qua
orientation request. Ảnh là render component Compose trên máy chủ, không ảnh máy thật.
Không suy FPS/pin/CPU Xiaomi từ thời gian render trên máy chủ.

## Cách tôi tự kiểm trên máy thật

1. Cài đè cùng khóa, xác nhận SAF/thư viện/playlist còn; thử chọn luôn ngang rồi
   mở/rời Cassette, tắt option và xoay hai chiều trong khi phát.
2. Kiểm không dừng nhạc/không khởi tạo player khi xoay; thử background/khóa màn hình.
3. Tên tiếng Việt rất dài và chữ200%, marquee/tua/thumb controls/TalkBack; cuộn
   cassette ra ngoài viewport, tắt hoạt ảnh hệ thống để kiểm nó ngừng quay.
4. DSF/DFF và PCM/FLAC/MP3: so chip với Chi tiết/đường tín hiệu, thiếu depth/bitrate
   phải ẩn; không coi AudioTrack là tần số DAC. Thử favorite/shuffle/repeat/queue.
5. Thử panel âm lượng/EQ, lỗi retry/details; xuất log như GĐ8 nếu cần gửi lại.
