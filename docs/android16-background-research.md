# GĐ2: phát nền trên Android 16 / HyperOS

Tài liệu thiết kế cho giai đoạn sau. **Chưa triển khai hoặc kiểm chứng các hành vi
phát nền trong GĐ1.** Thiết bị mục tiêu: Xiaomi 14 Pro, Snapdragon 8 Gen 3,
Android 16/HyperOS; targetSdk vẫn là 35 theo yêu cầu.

## Những điều tài liệu Android xác nhận

| Vấn đề | Hệ quả cho dự án | Nguồn chính thức |
|---|---|---|
| Android 16 áp quota job ngay cả khi chạy cùng/được khởi chạy từ FGS; thay đổi áp dụng cho mọi target | Không dùng service phát nhạc như cách miễn quota cho WorkManager quét thư viện. Ghi `WorkInfo.stopReason`; quét bị ngắt phải giữ chỉ mục và quét lại an toàn. | [Android 16: job runtime quota](https://developer.android.com/about/versions/16/behavior-changes-all#job-scheduler), [FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes), [Long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) |
| Giới hạn 6 giờ/24 giờ cho `dataSync` và `mediaProcessing` không phải giới hạn của `mediaPlayback` | Service phát nhạc khai báo đúng `mediaPlayback`, không dùng loại khác để mô tả phát âm thanh. Không suy ra service sẽ không bao giờ bị hệ thống dừng. | [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) |
| Media3 hỗ trợ player/session trong service độc lập Activity | Dùng `MediaLibraryService`/`MediaLibrarySession` (mở rộng `MediaSessionService`) và ExoPlayer. Khai báo FGS + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `stopWithTask=false`; vuốt task không chủ động dừng khi đang phát. | [Background playback](https://developer.android.com/media/media3/session/background-playback), [Serve library](https://developer.android.com/media/media3/session/serve-content) |
| Notification gắn với media session được miễn thay đổi quyền notification Android 13 | Không kết luận phát nhạc bị cấm vì người dùng từ chối `POST_NOTIFICATIONS`. Vẫn kiểm tra notification media và session trên Android 16 thật. | [Notification permission: media sessions](https://developer.android.com/develop/ui/views/notifications/notification-permission#media-sessions) |
| Người dùng bấm Dừng trong Active apps có thể kết thúc app mà không gửi callback | Không dựa vào `onDestroy` để ghi được mọi lần dừng. Phân biệt vuốt đa nhiệm, Stop Active apps và Force stop. | [Handle user stopping an FGS](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping) |

Không tự phát nhạc từ `BOOT_COMPLETED`: ứng dụng target 35 có hạn chế khởi chạy
`mediaPlayback` FGS từ broadcast này. Người dùng chủ động phát từ UI hoặc media
control trong trường hợp Android cho phép; kiểm tra đường khởi động đúng phiên bản
Media3 được khóa ở GĐ2.

## Màn hướng dẫn lần đầu, được bỏ qua

Chỉ thêm ở GĐ2. Chỉ dẫn bằng tiếng Việt, không hứa rằng các bước này bảo đảm app
không bị HyperOS kill. Tên/menu có thể khác giữa các bản HyperOS.

1. **Tối ưu pin Android**: kiểm tra `PowerManager.isIgnoringBatteryOptimizations`.
   Nếu chưa được miễn, giải thích ảnh hưởng và mở trang cài đặt pin phù hợp. Chỉ dùng
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` cùng quyền liên quan khi trường hợp
   sử dụng thực sự hợp lệ; không xin trực tiếp mặc định chỉ vì app phát nhạc hay
   dùng Bluetooth. [Doze: acceptable use cases](https://developer.android.com/training/monitoring-device-state/doze-standby#support_for_other_use_cases).
2. **Không hạn chế**: hướng dẫn vào Cài đặt → Ứng dụng → Quản lý ứng dụng → Walkman
   Studio Native → Tiết kiệm pin → Không hạn chế; xác nhận tên/menu trên máy thật.
3. **Tự khởi động**: hướng dẫn tìm mục Tự khởi động trong cài đặt ứng dụng/Quyền và
   bật cho app. Không tự mở khóa hay bật bằng API riêng của Xiaomi.
4. **Khóa trong đa nhiệm**: mở đa nhiệm, mở thao tác của thẻ app và chọn khóa nếu
   HyperOS cung cấp. Đây không phải bảo đảm chống Force stop.

Android không cung cấp API chuẩn để xác nhận cả ba trạng thái riêng của HyperOS:
Tự khởi động, Không hạn chế, khóa thẻ. UI phải ghi **“Bạn tự xác nhận”**, không hiển
thị trạng thái “Đã bật” suy ra từ model điện thoại. Có nút Bỏ qua và Xem lại hướng
dẫn. Các menu trên là checklist cần xác nhận, chưa phải quan sát từ Xiaomi 14 Pro.

## Log cục bộ có thể xuất

Kế hoạch GĐ2: ring buffer JSONL giới hạn dung lượng, nằm trong storage riêng;
không gửi mạng. Ghi thời gian wall clock + elapsedRealtime, process/session ID,
service create/start/taskRemoved/destroy, player state/error, focus change, noisy
intent và hành động dừng của app. Ghi lý do đã biết, không tự gán nguyên nhân.

Khi mở lại app trên API 30+, đọc lịch sử thoát của **chính app** bằng
`ActivityManager.getHistoricalProcessExitReasons`. Nếu hệ thống có
`ApplicationExitInfo`, bổ sung timestamp/reason/description; nếu thiếu, ghi
“Không có dữ liệu hệ thống”. Đây là bằng chứng lần mở sau, không phải phát hiện
kill tức thời. Không gán mọi `SIGKILL` hay process disappearance cho HyperOS.
[ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo),
[ActivityManager](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,int,int)).

Xuất log bằng SAF `ACTION_CREATE_DOCUMENT` khi người dùng chọn Xuất; không xin
quyền ghi rộng. Mặc định dùng ID nội bộ, tránh đưa đường dẫn thư viện đầy đủ vào log.

## Not verified

- Không có Xiaomi 14 Pro/HyperOS Android 16 kết nối với môi trường build.
- Chưa kiểm tra tên/menu pin, Autostart và khóa đa nhiệm trên máy của người dùng.
- Chưa phát nền, nhận nút headset/Bluetooth, thử Doze, notification hoặc Android Auto.
- GĐ1 không có service phát nhạc, onboarding pin hay log vòng đời playback.

## Cách tôi tự kiểm trên máy thật ở GĐ2

1. Phát nhạc từ app, tắt màn hình 30 phút; ghi model/build HyperOS, tuyến âm thanh
   và thiết lập pin. Thử loa, Bluetooth và tai nghe cắm máy riêng.
2. Vuốt task: nhạc đang phát tiếp tục; mở app lại kết nối đúng session/queue.
3. Đổi focus bằng cuộc gọi/app âm thanh khác; tháo tai nghe; kiểm tra chính sách
   pause/duck/resume và so sánh với log.
4. Thử Dừng trong Active apps và Force stop; app phải tôn trọng thao tác dừng.
   Mở lại rồi xuất log; không kỳ vọng callback ngay khi bị kill.
5. Lặp lại trước/sau các thiết lập HyperOS; lưu kết quả thay vì quy nguyên nhân từ
   một lần thử. Không dùng thay đổi ADB làm đại diện cho cấu hình mặc định.
