# GĐ3 — Tag, artwork, disc/track và ReplayGain metadata

Tạo MetadataReader/MetadataWorker/ArtworkCache; sửa Room schema2, migration1→2,
DAO sort/keyset/search, ScanStore invalidation, MediaItem mapping và danh sách UI.
Tag ID3v2/Vorbis/MP4 dùng Media3 extractor1.11.1; APE footer, DSF/DFF ID3 đọc qua
FileSource theo khối. Title/artist/album/albumArtist/track/disc/duration được lưu
thật, filename gốc giữ riêng trong Chi tiết. ReplayGain track/album gain+peak được
đọc từ TXXX/Vorbis/APE/MP4 freeform; áp dụng gain qua DSP ở GĐ5.

Metadata job đọc batch32, chỉ chọn metadataVersion còn thiếu; cập nhật theo lô,
kiểm tra stamp tránh ghi tag cũ lên nguồn đã đổi. Hủy/retry giữ các lô đã xong.
Quét file không đổi giữ tag; file mới/đổi đánh dấu cần đọc lại, giữ nghệ sĩ cũ nếu
đọc tag lỗi. Migration không xóa dữ liệu hoặc đổi numeric ID/URI grant.

Bìa nhúng ưu tiên, sau đó cover.jpg/folder.jpg cùng thư mục. Cache đĩa theo hash,
hai tier128/512, ngân sách96MiB; RAM bitmap UI32MiB. Không tải mạng, không demoart.
Giới hạn metadata16MiB, ảnh8MiB, chặn moov/ID3 quá lớn trước khi extractor cấp phát.
Không đặt trần số bài. Tìm kiếm title/artist/album/name qua Room và Paging.

GĐ3–6 build/test/lint variant debug để cùng lớp mã với unit test; GĐ2 đã kiểm
review/release và GĐ7 sẽ kiểm release/R8. Lệnh gate ghi rõ trong JSON, không bỏ
test hay tắt lint; chỉ tag/tiếp tục khi gate trả0.

## Not verified
Chưa thử tag/artwork/bộ mã ký tự trên thư viện Xiaomi thật hoặc đo tốc độ100k tag
thật. MP4/Ogg dựa trên parser Media3; fixture riêng bao phủ ID3/FLAC/APE/DSF/DFF,
mapping metadata MP4/Vorbis, cache ảnh và migration Room. Các tag/container bất
thường có thể trả metadataError; playback không bị kết luận file hỏng. Folderart
đổi mà audio size/mtime không đổi cần dùng quét lại toàn bộ thông tin. Nguồn pipe
có thể seek chậm. Chưa kiểm ảnh notification/Android Auto trên thiết bị.

## Cách tự kiểm trên máy thật
Chạy quét lại; đối chiếu Hello/Adele/25 với tag gốc, disc1/2 và track2/10. Thử
MP3ID3v2.3/v2.4, FLAC/Vorbis, M4A/MP4, APE và DSF/DFF ID3; ảnh nhúng và thư mục
có cover.jpg/folder.jpg. Đối chiếu duration/ReplayGain trong Chi tiết. Sửa tag,
quét lại; xác nhận numeric ID/SAF grant và file gốc giữ nguyên. Đổi cover ngoài
rồi dùng quét thông tin đầy đủ. Chưa dùng phần này để xác nhận DSD phát được.

## Gate đã chạy
Build debug + unit test + lint cả app/library/playback trả0; 86/86 test,
không lỗi hoặc bỏ qua (core31, library35, playback13, app7).
Chi tiết: qa/stage3-gate.json và qa/stage3-test-results.json. Đây là chạy trên
host/Robolectric, không phải kết quả phát nhạc hay quét tag trên Xiaomi.
