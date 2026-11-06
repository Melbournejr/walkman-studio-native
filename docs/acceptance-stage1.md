# Nghiệm thu GĐ1

Phạm vi: dự án Kotlin/Compose/Room, SAF chỉ đọc, FileSource, quét tăng dần, Paging
và danh sách tên file. App ID `com.walkmanstudio.nativeplayer`, cài song song bản
WebView. Chưa phát nhạc, chưa đọc tag/bìa, chưa có DSD native/EQ.

## Tiêu chí tự động

- Build APK với minSdk 26, targetSdk 35; manifest hợp nhất không có `INTERNET`,
  `MANAGE_EXTERNAL_STORAGE` hoặc quyền đọc/ghi storage rộng; `allowBackup=false`.
- Core test scanner thực: hơn 100.000 mục sinh theo luồng; batch tối đa 512;
  không mở/nạp nội dung file nhạc; hủy/lỗi/mất quyền không prune.
- Room integration: insert mới, không đổi, đổi size/mtime, metadata không rõ,
  xóa mục chỉ sau quét thành công, generation/root guard chống worker cũ ghi lại.
- Benchmark production scanner + Room file-backed với 100.000 mục giả: quét đầu,
  quét không đổi và 1% thay đổi. Ghi thời gian/môi trường vào tài liệu PR và JSON.
  Ghi rõ đây là host/Robolectric, không phải tốc độ provider SAF hay Xiaomi.
- Natural sort không ép dãy số dài vào Int/Long, tên `track2` trước `track10`.
- Chỉ số lần quét/tổng số mục dùng Long; không đặt trần cứng số file.

Kết quả thực chạy do người thực hiện build ghi trong PR; checklist không tự xác
nhận các tiêu chí đã pass.

## Not verified

- Chưa đo provider SAF bộ nhớ trong trên Xiaomi 14 Pro, Android 16/HyperOS.
- Chưa xác nhận độ mượt ở 100.000 file thật, chi phí Binder/cursor/I/O và quota job.
- Chưa xác nhận TalkBack, font lớn, xoay và khôi phục process trên thiết bị thật.
- Worker thường có giới hạn thời gian/quota. Nếu bị ngắt, giữ chỉ mục và lần quét
  sau liệt kê lại; chưa có checkpoint cursor SAF liên tiến trình.
- Không coi file DSF/DFF hiển thị trong thư viện là đã phát/đọc tag được.

## Cách tôi tự kiểm trên máy thật

1. Cài APK GĐ1 bằng trình cài Android hoặc `adb install -r <apk>`. Xác nhận tên app
   Native xuất hiện riêng bản cũ; không gỡ bản cũ.
2. Tạo thư mục thử trong bộ nhớ trong với `track1.mp3`, `track2.flac`,
   `track10.wav`, thư mục con và file không phải âm thanh. Dùng bản sao mẫu nhạc
   hoặc file do bạn tự tạo, giữ bản gốc an toàn.
3. Trong app chọn thêm thư mục, cấp quyền SAF. Chỉ file nhạc được lập chỉ mục;
   tên file còn nguyên, thứ tự tự nhiên đúng. Không có artist/bìa giả.
4. Tìm một tên, chọn riêng root, cuộn danh sách; đổi sáng/tối và xoay màn hình.
   Tăng cỡ chữ và bật TalkBack: tên/nút đọc được, thao tác không bị che.
5. Quét lại ngay: số mục ổn định, mục không đổi không được xử lý lại như mục mới.
   Thêm/đổi một file bằng ứng dụng Files rồi quét lại: chỉ thay đổi tương ứng xuất
   hiện; provider có metadata không rõ có thể được xử lý lại thận trọng.
6. Quét tập lớn rồi bấm Hủy: dữ liệu đã có không bị xóa hàng loạt. Quét lại phải
   hoàn tất an toàn; quan sát trạng thái nếu WorkManager bị hệ thống hoãn/dừng.
7. Xóa một bản sao file từ ứng dụng Files, quét hoàn tất: mục biến mất khỏi index.
   Nếu quét lỗi/hủy trước hoàn tất, không được prune các mục chưa duyệt.
8. Bấm bỏ root khỏi thư viện và xác nhận: index/root biến mất, file gốc trong
   Files vẫn còn. Thử bỏ root khi đang quét; worker cũ không được làm nó xuất hiện lại.
9. Với app không có dữ liệu cần giữ, thu hồi grant SAF (nếu ROM cung cấp), hoặc
   dùng môi trường provider thử; quét lại phải yêu cầu chọn lại, không xóa nhạc.
   Xóa dữ liệu app cũng xóa grant nhưng không dùng cách này để chứng minh phục hồi
   index, vì database của app bị xóa cùng lúc.
10. Với thư viện thật ≥100.000 file, ghi số bài, thời gian từ bắt đầu đến hoàn tất,
    lần đầu/không đổi/1% thay đổi, trạng thái pin và nhiệt máy. Tách thời gian quét
    khỏi thời gian cuộn/tìm kiếm. Không suy số đo host thành kết quả điện thoại.

Nếu tốc độ SAF thực tế không đạt nhu cầu, đánh giá MediaStore hoặc quyền truy cập
mọi tệp trong thay đổi riêng có người dùng chọn. GĐ1 không xin/bật các lựa chọn đó.
