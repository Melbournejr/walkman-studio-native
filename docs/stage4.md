# GĐ4 — Giao diện và thư viện cá nhân

Tạo Collections.kt (playlist/selection/keyset/folder/album), PlaybackCommands.kt;
sửa Room3 + migration2→3, LibraryRepository/ViewModel/Screen, PlaybackViewModel,
PlaybackService, LibraryArt, PlaybackPanel và vector icons. Thêm animation Compose
cùng BOM2025.10.01; các dependency khác giữ version catalog.

Bottom bar4 mục, mini player72dp, mở Đang phát bằng shared artwork transition,
layout ngang/dọc, bìa112dp ở collection, tên16sp/nghệ sĩ13sp, control48dp/play64dp.
Danh sách giữ menu ⋮. Bìa thiếu dùng identicon hash theo album/nghệ sĩ/thư mục.
Settings lưu sáng/tối/hệ thống; Help HyperOS và xuất log giữ nguyên. Queue, sleep
15/30/60/90phút, lỗi giữ artist/retry/detail. Sleep dùng monotonic clock của
service, hết hạn pause; không tồn tại qua process death (vị trí vẫn được lưu).

Room lưu yêu thích/playlist, thứ tự thêm playlist, FK cascade khi xóa chỉ mục.
SAF thư mục lồng nhau suy từ đường dẫn tương đối đã index; không có hộp thư mục
rỗng khi là leaf. Album nhóm album+albumArtist/artist. Search tên/tag với literal
%/_; không ghép đầu vào vào SQL. Paging hiển thị tối đa800 row trong cache.

Service sở hữu filter đang phát; lấy200 bài đầu rồi keyset100 bài tiếp, giữ50 bài
trước. Không trần thư viện. Shuffle SQL thứ tự xác định theo seed, đi qua toàn bộ
selection thay vì chỉ200 bài đang tải. Queue chỉnh tay chuyển sang queue cụ thể;
phần cửa sổ lưu tối đa300 bài, filter được lưu để nối tiếp khi mở lại. Lặp một
bài; chưa có lặp toàn bộ thư viện vô hạn. Android Auto browse vẫn phân trang200.
Metadata hiện tại được Room Flow đẩy vào MediaItem để notification đọc tag mới.

## Build và test
`python qa/gate.py 4` (hoặc các task ghi trong qa/stage4-gate.json với ./gradlew).
Room tests: folder/prefix có %/_, direct/recursive, selection>window1201 bài cả
shuffle không mất/trùng, favorite/tag search, playlist order/duplicate/cascade,
full migration1→3; các test GĐ1–3 giữ nguyên. Không Ignore hay lint baseline.
Nghiệm thu host: build, tất cả test, lint đều0; không thay cho test trên điện thoại.

## Not verified
Chưa chạy UI/TalkBack/font200%/xoay trên Xiaomi, chưa nghe queue/gapless/sleep
khi tắt màn hình. Thư mục trống không có nhạc không xuất hiện vì chỉ mục là bài
hát. Tên thư mục có ký tự slash do provider trả có thể không biểu diễn duy nhất
bằng path, nhưng mở tệp vẫn dùng documentId. Album không tag nằm trong Bài hát /
Thư mục. Chưa chứng minh notification/Auto cập nhật hình trên thiết bị.

## Cách tôi tự kiểm trên máy thật
Thêm root có album/Disc1/Disc2; mở từng folder, thử Phát tất cả/Ngẫu nhiên; đặt
playlist gồm bài từ nhiều root rồi đóng/mở lại. Sửa/xóa tệp và quét lại, favorite
không đổi khi file stamp đổi. Thử search dấu tiếng Việt/%/_. Mở mini→Đang phát,
xoay, TalkBack và font200%, mọi control phải chạm được. Thử sleep15phút khi khóa
màn hình và xuất log có sleep_timer_expired. Notification phải giữ title/artist
khi lỗi. Nối tiếp sau bài200 cần thử trên queue dài.

## Gate đã chạy
Build debug, 92/92 unit test và lint app/library/playback đều trả0.
Xem qa/stage4-gate.json và qa/stage4-test-results.json. Không có test bỏ qua.
