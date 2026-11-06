# Core — GĐ1

Module Kotlin thuần, không Android/Room/network. `FileSource` và `SourceHandle`
là hợp đồng đọc file; triển khai SAF nằm ở `:library`.

`LibraryScanner` chỉ đọc danh sách và metadata filesystem, chưa đọc tag hay giải
mã âm thanh. Scanner xử lý lô 256 (cấu hình tối đa 512 để giới hạn giao dịch SQL),
không đặt trần số file. Lô file giữ RAM giới hạn; tập ID thư mục chống chu kỳ và
stack thư mục có chi phí tỷ lệ với số thư mục, không số bài hát. Chỉ một cursor
thư mục hoạt động tại một thời điểm.

Quét tăng dần vẫn phải liệt kê thư mục: adapter chỉ cập nhật nội dung bản ghi khi
size/lastModified hoặc đường dẫn/tên thay đổi; trường thiếu bị xem là thay đổi.
Chỉ `complete()` được prune, sau khi đã đọc thành công toàn bộ cây. Lỗi provider,
hết quyền hoặc hủy gọi `interrupted()` không hủy bookkeeping. Không xóa nhạc gốc.

`naturalSortKey()` dùng NFKC/Locale.ROOT và độ dài chuỗi chữ số thay vì parse Long.
SQLite dùng BINARY collation; thêm name/documentId làm tie-breaker ổn định.

Kiểm thử: `./gradlew :core:test`. Bao gồm nguồn sinh 100.001 mục streaming,
backpressure giới hạn batch, lỗi/quyền/hủy cursor, thư mục 2.048 cấp và tệp DSD
500 MB chỉ qua metadata. Đây không phải benchmark SAF hay thiết bị Xiaomi.

Not verified: thời gian provider thật, hành vi SAF trên Android, thông lượng Room,
RAM/process trên điện thoại. Các phần này thuộc kiểm thử adapter và máy thật.
