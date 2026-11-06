# GĐ2 — Media3 và service phát nền

Tạo `playback/`: SAF-only DataSource, MediaLibraryService (lớp con MediaSessionService),
MediaLibrarySession, hàng đợi theo cửa sổ, snapshot vị trí và log JSONL có vòng đệm.
Sửa DAO để lấy bài theo ID/cửa sổ; app thêm MediaController, điều khiển cơ bản,
hướng dẫn Xiaomi/HyperOS và SAF CreateDocument để xuất log. Media3 1.11.1; không
FFmpeg/WASM/WebView, không Internet, không MediaStore hoặc quyền lưu trữ rộng.

Player playlist liên tục, MP3 bật index seeking; không khởi tạo lại decoder giữa
chuyển bài tự động. Media3 xử lý focus/noisy intent/wake lock và media notification.
Manifest `mediaPlayback`, `stopWithTask=false`; task swipe giữ yêu cầu phát/buffering.
Lưu vị trí mỗi 2 giây và khi đổi trạng thái/tua, không tự phát khi service được tạo.
Khôi phục ID từ Room, bỏ ID đã bị xóa; URI nhận từ controller không được tin cậy.
Không hỗ trợ crossfade. Định dạng mục tiêu MP3/FLAC/WAV/AAC/OGG phụ thuộc extractor
và decoder thiết bị; không giả định có mọi codec trên mọi máy.

Log gồm create/start/destroy/taskRemoved, lý do do app biết, focus suppression,
noisy, state/error/seek/transition. Hai tệp riêng, mỗi tệp tối đa 256 KiB. API30+
đọc ApplicationExitInfo lần mở sau khi có dữ liệu; không hứa ghi đúng tức thời lúc
process bị kill. Không gán lý do không biết thành HyperOS. Xuất chỉ khi người dùng
chọn đích SAF; không upload và không ghi đường dẫn đầy đủ vào sự kiện mặc định.

Nguồn Android chính thức và giới hạn HyperOS: `android16-background-research.md`.
API đã đối chiếu mã nguồn Media3 1.11.1: onConnectAsync, onPlaybackResumption ba
tham số, AcceptedResultBuilder(session, controller). Không dùng các overload cũ.

Build/test/lint: `qa/gate.py 2` (wrapper và Android SDK/JDK phải sẵn sàng). Chỉ
commit/tag stage2 và tiếp tục khi mọi lệnh trong gate trả 0. Gate thất bại: dừng,
ghi FINAL-REPORT; không tạo tag đạt giả.

## Not verified
- Chưa chạy nghe âm thanh thực, đo gapless, độ chính xác seek MP3/VBR hoặc codecs
  trên Xiaomi14Pro. Robolectric không chứng minh chất lượng/timing của AudioTrack.
- Notification, khóa màn hình, Bluetooth/headset, Android Auto, audio focus,
  Doze/HyperOS/task swipe và system kill cần máy thật.
- Tên menu HyperOS là hướng dẫn cần người dùng xác nhận; không tự dò ba thiết lập
  riêng của Xiaomi. Trạng thái Android tối ưu pin chỉ đọc được qua API chuẩn.

## Cách tôi tự kiểm trên máy thật
1. Phát từng MP3/FLAC/WAV/AAC/OGG và thử tua đầu/giữa/cuối. Kiểm tra cặp album
   liên tục có encoder delay/padding hợp lệ, nghe và ghi nhận khe hở chuyển bài.
2. Tắt màn hình, vuốt đa nhiệm khi đang phát; thử trước/sau hướng dẫn HyperOS.
3. Dùng media notification/khóa màn hình/Bluetooth/headset; thử cuộc gọi và tháo tai nghe.
4. Đóng/mở app, đối chiếu bài và vị trí (mất tối đa khoảng chu kỳ snapshot khi kill).
5. Bấm Xuất log, chọn đích SAF; thử Active apps Stop/Force stop rồi mở lại xuất log.
   Không kỳ vọng onDestroy luôn chạy; đối chiếu historical exit nếu hệ thống cung cấp.

## Kết quả gate cuối GĐ2
Build review + 69/69 test + lint app review/library release/playback release đều trả 0.
Fixture giữ service/MediaSession/MediaController thật; chỉ thiết lập boundary bindService và identity của hệ điều hành trong Robolectric. Đã sửa hai alias hằng lỗi sang SessionError đúng API; không thêm null guard, Ignore, baseline hoặc hạ assertion.
