# GĐ1 — Thư viện SAF native, FileSource và quét tăng dần vào Room

Trạng thái: bản mô tả PR để review trong gói nguồn. Dự án chưa có remote GitHub,
nên chưa tạo một pull request trên GitHub. Không thay đổi ứng dụng WebView 1.1.1.

## Thay đổi và hành vi

Tạo ứng dụng Kotlin/Compose Material 3 cài song song, application ID
`com.walkmanstudio.nativeplayer`, minSdk26/targetSdk35. Người dùng chọn thư mục qua
SAF; app giữ persistable **read** grant và chỉ lưu URI/chỉ mục trong Room. Không
copy nhạc, không xin INTERNET hoặc quyền đọc toàn bộ bộ nhớ.

`FileSource` tách provider khỏi bộ quét; triển khai đầu là SAF. Scanner duyệt
streaming, lô 256, cập nhật theo size/mtime và thay đổi tên/vị trí. Metadata không
đủ được xử lý bảo thủ. Một lượt quét thất bại, bị hủy, mất quyền, cursor loading
hoặc cursor có EXTRA_ERROR không được prune. Generation lease cùng transaction
ngăn worker cũ ghi lại root đã xóa. Bỏ thư mục chỉ xóa chỉ mục và grant của app.

Compose đọc Paging, tìm tên file và lọc thư mục gốc. Hàng 72dp, tên 16sp, mục phụ
13sp, vùng chạm ≥48dp; có sáng/tối, trạng thái quét/hủy/thử lại, thông báo mất
quyền, xác nhận bỏ chỉ mục và Chi tiết giữ tên file/URI gốc. GĐ1 chưa có tag hay
phát nhạc. Không hiển thị bìa demo hoặc nghệ sĩ suy đoán.

Các tệp đầy đủ được liệt kê ở `docs/files-stage1.md`; toàn bộ mã, wrapper, schema
Room và test nằm trong gói nguồn, không chỉ là đoạn minh họa.

## Benchmark 100.000 mục

Đã chạy production `LibraryScanner` + `RoomScanStore` với Room **file-backed**,
WAL và native SQLite trên Robolectric 4.16.1/API 35. Nguồn sinh streaming 100.000
file/100 thư mục con, không tạo 100.000 file nhạc vật lý và không đọc payload. Không
có trần 100.000 trong production.

Host Linux x86_64, Intel Xeon Platinum 8573C, quota 2 vCPU, container 8 GiB, JVM test 2 GiB;
Runtime báo 3 processor khả dụng. Đây **không phải** Snapdragon 8 Gen 3 hoặc Android 16.

| Kịch bản | Lượt1 | Lượt2 |
|---|---:|---:|
| Quét đầu,100.000bản ghi mới | 8,918s | 3,550s |
| Quét lại,0file đổi | 6,173s | 2,643s |
| Quét lại,1.000file đổi | 5,594s | 2,834s |
| Tải trang đầu 80 hàng qua Paging | 50,639ms | 68,670ms |

Hai lượt chạy trong các lần build/test khác nhau, JIT/cache/tác vụ cạnh tranh khác
nhau; không lấy trung bình hoặc suy ra phân bố độ trễ. Lượt2 được lưu nguyên ở
`qa/benchmark-room-100k.json`; thông tin host ở `qa/host-environment.json`.
Query plan dùng `index_tracks_rootId_naturalSortKey_name_documentId`, không sort
bằng bảng tạm. Lượt2 DB+WAL khoảng 47,2 MB. Heap dùng sau test khoảng 93,6 MB,
**không phải peak RAM** và không phải mức dùng bộ nhớ app trên điện thoại.

Số trên chưa gồm SAF/Binder/provider, WorkManager scheduling/progress, đọc tag,
giải mã hay vẽ/scroll UI. Quét không đổi vẫn liệt kê SAF và cập nhật marker thấy
file; không có changefeed SAF chung để bỏ bước này.

## Nếu SAF thực tế chậm đến vài phút

Chưa có số đo SAF trên Xiaomi để quyết định đổi nguồn. Không bật thêm quyền.

| Lựa chọn tùy chọn sau khi đo máy thật | Ưu điểm | Đổi lại |
|---|---|---|
| MediaStore | Truy vấn chỉ mục hệ thống, ít duyệt cây/Binder hơn; thích hợp thư viện âm thanh phổ biến | Phụ thuộc hệ thống đã index; có thể thiếu DSD/định dạng lạ; quyền media và ngữ nghĩa lọc thư mục khác SAF |
| MANAGE_EXTERNAL_STORAGE | Duyệt filesystem bộ nhớ chung trực tiếp, kiểm soát batching/stat tốt hơn | Quyền rất rộng, người dùng phải cấp trong Settings; vẫn không vào storage riêng của app khác; cần thêm triển khai và kiểm thử |

Chỉ triển khai các lựa chọn này sau khi người dùng chọn rõ. Trước hết dùng benchmark
SAF thật để biết nghẽn ở provider, SQL hay UI; không dùng số đo host để kết luận.

## Build và kiểm tra

Lệnh chuẩn trong README; 56 test đã qua (core 31, library 18, app 7); kết quả JUnit thực được tổng hợp ở
`qa/test-results.json`. Kiểm tra identity/min/target/allowBackup/quyền từ APK đóng
gói bằng `qa/check-apk.py`. APK tải xuống là variant review được thu gọn bằng R8,
ký bằng khóa debug để nghiệm thu, không phải release ký khóa của người dùng.

Smoke test chạy Application/Koin/ViewModel/Compose và recreate Activity trên host.
Robolectric không tự chạy Startup provider trong bootstrap này: test kiểm tra
manifest provider/initializer rồi khởi tạo **WorkManager thật** bằng defaultconfig.
Robolectric chưa hỗ trợ overload PackageManager typedflags; test host dùng overload
intflags có suppress/comment, production không dùng API đó.

Pipe test dùng `java.nio.channels.Pipe` thật và cùng thuật toán production
`ReopenableSourceHandle`. Không dùng createPipe shadow của Robolectric vì shadow
này tạo RandomAccessFile seekable. Adapter PFD được test riêng với regularfile.

## Not verified

- Chưa cài/chạy trên Xiaomi 14 Pro Android 16/HyperOS; chưa đo SAF với100.000file thật,
  độ mượt scroll, TalkBack, bàn phím, xoay màn hình hoặc font lớn trên máy thật.
- Chưa kiểm tra persistedgrant sau reboot hoặc quyền bị thu hồi trên provider thật.
- Chưa kiểm chứng ParcelFileDescriptor pipe của DocumentsProvider Android thật;
  hostpipe chỉ kiểm tra thuật toán skip/reopen/EOF.
- Worker thường chịu runtime/quota. Chưa có checkpoint cây/cursor bền vững giữa
  worker: quét lại giữ các lô đã ghi nhưng liệt kê lại nguồn. Không cam kết thư viện
  bất kỳ kích thước nào đều quét xong trong một worker.
- Chưa benchmark tìm kiếm substring trên 100.000 tên với SAF thực. `LIKE` có thể quét
  chỉ mục/tập bản ghi; FTS sẽ được đánh giá cùng metadata ở GĐ3.
- GĐ1 chưa có playback/service/notification/DSD/EQ; không có bằng chứng gapless,
  phát nền HyperOS hoặc hiệu năng DSD. Nghiên cứu Android 16 trong docs là kế hoạch.
- APKreview R8 chưa được chạy trên điện thoại; build/signature/manifest không chứng
  minh runtime hoạt động trên thiết bị đó.

## Cách tôi tự kiểm trên máy thật

1. Cài APK thử nghiệm; app phải xuất hiện riêng với bản 1.1.1. Không có yêu cầu mạng
   hoặc truy cập mọi tệp. Chọn một thư mục nhạc nhỏ bằng trình chọn hệ thống.
2. Đối chiếu tên/số file với thư mục; thử tìm tên, lọc root, sáng/tối, xoay màn hình
   và tăng font. Chi tiết phải giữ tên file gốc. GĐ1 chưa phát nhạc.
3. Quét lại không đổi; thêm/sửa/xóa một số file rồi quét lại, đối chiếu số thay đổi.
4. Hủy giữa chừng, thử lại; chỉ mục cũ không biến mất. Thu hồi quyền hoặc làm nguồn
   không đọc được rồi quét: app báo lỗi/quyền, không kết luận file hỏng hoặc xóa index.
5. Bỏ thư mục khỏi thư viện rồi kiểm tra bằng Files: nhạc gốc vẫn còn. Cấp lại và
   quét, không có worker cũ hồi sinh dữ liệu sau khi root bị bỏ.
6. Thử thư viện 100.000 file nội bộ thật, ghi thời gian từ bấm quét đến hoàn tất, số
   file/thư mục/build HyperOS và quét đầu/quét lại riêng; không dùng hostbenchmark
   làm con số kỳ vọng. Checklist chi tiết ở `docs/acceptance-stage1.md`.

## Các giai đoạn sau

Chờ người dùng nghiệm thu GĐ1 trước GĐ2. Gapless ở GĐ2; crossfade **sau GĐ7**.
Trước GĐ6 phải giao danh sách phép thử DSD thật cùng cách xuất log. Benchmark
DSD64/128/256 mục tiêu decode/duration < 0,25x cần chạy trên Snapdragon 8 Gen 3 thật;
mọi mô phỏng sẽ được ghi rõ, không thay thế kết quả thiết bị.

GPL-3.0-or-later, giữ LICENSE/NOTICE. GĐ1 không kèm mã DSD/FFmpeg. GĐ6 sẽ ghi nguồn
trích dẫn khi port từ bản cũ; không đưa khóa ký của người dùng vào repository/gói nguồn.
