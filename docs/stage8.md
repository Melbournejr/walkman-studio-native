# GĐ8 — Cassette, đĩa than và hai tùy chọn âm thanh

Nhánh `stage8/cassette-vinyl`. Mục tiêu sau gate: một APK `walkman-native-stage8.apk`.
Chỉ tạo commit/tag `stage8` khi build, tất cả test và lint đều đạt; gate lỗi thì dừng.

**ĐẠT:** gate release/review/R8/lint và148/148 test,0fail/error/skipped; thời gian gate760.868giây.
Preferences fixture dùng `android.app.Application`, vì đọc/ghi preferences không cần
khởi động DI; MainActivitySmokeTest vẫn dùng Application production thật. Không
thêm guard `startKoin` vào production. Capture fixture dùng `View.draw(Canvas)`
trên bitmap Skia native thay đường chờ callback cửa sổ; Compose1.9.4 software
Canvas vẫn áp dụng transform/clip. Giữ mọi assertion400×480/diversity/hash8ảnh,
input và chuyển động; không tăng timeout hoặc thay pixel giả.

Test SCREEN_OFF phải xử lý hàng đợi Handler Android bằng ShadowLooper.idle trước
khi đẩy clock Compose/lấy baseline. Log lần3 đã khớp đúng receiver; callback được
post bất đồng bộ. Thêm assertion OS không interactive và đã phát SCREEN_OFF,
không bỏ assertion hash đứng yên. Đây là đồng bộ fixture, không sửa production.
Lịch sử lỗi ở `qa/stage8-failure/` được giữ; không dùng kết quả partial để tag.

**Cách sửa fixture clock đã chọn:**
Chọn `System.nanoTime()` vì render phụ thuộc Android/Compose, không chuyển một
benchmark CPU thuần JVM sang core rồi gọi đó là thời gian giao diện. Đo riêng từng
lần cập nhật tiến độ + render/readback, chỉ báo ms (mean/median/p95/min/max và60
mẫu mỗi chế độ); không báo CPU%, pin hay suy hiệu năng Xiaomi14Pro. Pixel hashing
và PNG encoding nằm ngoài khoảng đo. Đây không phải thời gian GPU thuần hoặc FPS
animation liên tục. Helper đồng hồ chỉ trong test, tránh interceptor đồng hồ ảo
của Robolectric PAUSED. Không sửa production để fixture qua.

Thay các assertion về bộ đếm CPU không dùng được bằng kiểm tra delta đồng hồ thực
dương ở mọi frame. Giữ nguyên assertion400×480, pixel diversity, hash8ảnh khác nhau,
tua/TalkBack, chuyển động và đứng yên; không Ignore/xóa test/hạ ngưỡng chức năng.
Lỗi compile ở lần trước giữ tại `qa/stage8-failure/` (history); lần2 đã qua compile
nhưng lỗi runtime ở các fixture nêu trên. Không dùng kết quả partial làm gate8 đạt.

Không đổi Media3/SAF routing, FIR C++, parser, seek, EQ/ReplayGain hay thư viện Room.
Hai điểm chạm âm thanh giới hạn ở phép nhân float sau FIR DSD và lượng tử hóa cuối DSP.

## Giao diện

Cài đặt lưu Cổ điển/Cassette/Đĩa than vào preferences; thiếu/không biết giá trị thì
Cổ điển. Bốn palette hiện có áp dụng cho cả ba chế độ. Chỉ màn Đang phát có đồ họa.
Cassette và rãnh đĩa đều vẽ Compose Canvas/vector; không ảnh nền/bitmap cassette,
không logo hoặc tiếng động giả. Nhãn đĩa dùng bìa album hiện có, clip tròn.

Băng giữ tổng diện tích cuộn (bán kính bình phương), cuộn nguồn nhỏ dần/cuộn nhận
lớn dần theo tiến độ. Có nhãn bài/nghệ sĩ, counter thời gian và phản chiếu nhẹ.
Đĩa quay 200°/giây = 33⅓ rpm; cần kim đi từ ngoài vào trong theo tiến độ, nhấc khi
tạm dừng/tua. Thay góc/nâng kim là trạng thái trực tiếp, không tự chạy hoạt ảnh khi dừng.
Trước/sau56dp, phát64dp; vuốt ngang đồ họa preview vị trí, thả mới gửi một seek.
Giữ slider; semantics có tiến độ, thời gian, hành động TalkBack tiến/lùi10giây.

Frame clock chỉ được yêu cầu khi đang phát, không tua, lifecycle≥STARTED, màn hình
interactive, không khóa và hệ thống cho phép hoạt ảnh. Thay trạng thái hủy coroutine;
không catch-up thời gian ở nền. Theo dõi screen broadcasts và animator duration scale.
Phần này không dừng nhạc hoặc sửa player. Thông tin thời gian render host ghi riêng trong
`qa/stage8-ui-benchmark.json` sau khi chạy test; không suy ra FPS/pin của Xiaomi.

## Bù mức DSD và dither

Bù mức mặc định0dB, chỉ nhận0/+3/+6. Sau FIR C++ nhân các float DSD bằng
`10^(dB/20)` trước khi gửi TrackOutput. 0dB bỏ qua byte-exact; không thêm hardclip
trong extractor. Không có lời gọi này trong decoder/processor PCM thường. Source
Direct vẫn bỏ qua DSP; gain DSD đã chọn vẫn áp dụng, có giải thích ở Cài đặt.

Bộ đếm dùng `abs(sample)>1` sau gain, đếm mẫu-kênh giải mã, kể cả đọc trước/tua lại.
Reset khi thực sự đổi mức hoặc tạo service; không reset nếu chọn lại cùng mức.
Đây không phải phép đo clipping tại DAC. Gain đổi áp dụng cho khối giải mã tiếp theo;
audio đã đệm có thể vẫn mang mức cũ. Log `dsd_open`, `dsd_stats`, `dsd_level` và
session extras có thông tin mức/bộ đếm. Không thay thuật toán FIR hoặc ngưỡng GĐ6.

TPDF mặc định bật; hai uniform độc lập lấy hiệu, ±1LSB trước round/saturate PCM16.
Chỉ ở lượng tử hóa cuối sau EQ; Source Direct không tạo processor và không lấy
ngẫu nhiên/thêm noise. Tắt dither giữ phép lượng tử hóa cũ. Đổi dither không khởi
động lại ramp EQ đang chạy. Preferences cũ thiếu trường dither được đọc là bật.
Media3 1.11.1 vẫn đổi nguồn 24-bit/float về PCM16 trước custom AudioProcessor;
dither cuối không sửa lần đổi đầu hoặc khôi phục bit đã mất. Không đổi đường phát.

## Test và tiêu chí nghiệm thu

Giữ toàn bộ test/assertion cũ. Fixture kiểm DSP không nhiễu đặt `dither=false`,
thêm test riêng cho mặc định bật và đường có nhiễu. TPDF kiểm mean silence≤0.003LSB,
DC dương/âm≤0.005LSB, mean error sin≤0.004LSB; power ratio sin sai số≤1e−5.
Kiểm phân phối, độc lập kênh, phân khối lẻ, ramp và Source Direct byte-exact.

DSF/DFF64/128/256 mono/stereo kiểm mặc định/0dB byte-exact và float +3/+6,
bộ đếm, giới hạn buffer, reset, lưu lựa chọn. Các test FIR/reference/seek cũ giữ nguyên.
Hình học kiểm bảo toàn diện tích băng, rpm, vùng kim, bounds seek và mọi tổ hợp
visibility/motion. Host render test phải tạo ảnh có pixel thật, không mô tả preview
chưa chạy. Chứng cứ chỉ được cập nhật sau gate.

```sh
python3 qa/gate.py 8
python3 qa/check-final-apk.py /duong-dan/walkman-native-stage8.apk --stage 8 --previous-apk /duong-dan/walkman-native-final.apk
```

Gate8 build release + review/R8, test4module (không skipped), lint app release/review
và library/playbackrelease. Review dùng khóa cài thử GĐ7, versionCode101 lớn hơn100;
kiểm V2 signature và chứng thư bằng APK GĐ7 đã giao, không chỉ tên keystore. Không
tạo khóa trong repo. Khóa riêng người dùng nếu đã tự ký GĐ7 sẽ khác khóa cài thử:
phải tự ký GĐ8 bằng chính khóa đó. Không hứa cập nhật đè hai APK khác chứng thư.

## Not verified

Không có Xiaomi14Pro/emulator/Android thật để cài cập nhật và phát nhạc. Chưa đo
CPU/GPU/FPS/pin Snapdragon, nghe gain/dither/DSD, clipping DAC, độ trễ do buffering,
TalkBack/font200%/cử chỉ/xoay/khóa màn hình/giảm hoạt ảnh trên HyperOS. Ảnh và ms/frame
trong test là Robolectric/JVM host, không phải ảnh chụp hay hiệu năng trên điện thoại.

## Cách tôi tự kiểm trên máy thật

1. Giữ GĐ7 đã cài; cài đè GĐ8 cùng khóa, kiểm thư viện/SAF/playlist còn nguyên.
2. Chọn từng kiểu Đang phát và4theme, khởi động lại; kiểm lưu lựa chọn, xoay,
   font200%, TalkBack và vuốt/slider tua. Kim phải nhấc khi tua và khi tạm dừng.
3. Phát/tạm dừng, về nền, tắt/khóa màn hình, bật loại bỏ hoạt ảnh trong hệ thống.
   Khi không hiển thị/giảm hoạt ảnh, đồ họa không tiếp tục quay; nhạc vẫn phát.
4. DSF/DFF lần lượt0/+3/+6dB, so PCM thường không bị bù; xem bộ đếm và thử Source
   Direct. Chờ bộ đệm cũ hết trước khi so mức, giảm âm lượng nếu tín hiệu vượt ngưỡng.
5. Bật EQ, thử bật/tắt TPDF; kiểm Source Direct không đổi. Chạy lại checklist GĐ6
   và xuất **Cài đặt → Xuất log trình phát**, gửi thao tác/model/OS/đường ra/thời điểm.

## Kết quả gate và ảnh đính kèm

Chứng cứ: `qa/stage8-gate.json`, `stage8-test-results.json`, `stage8-apk.json`,
`stage8-signature.txt`, `stage8-ui-benchmark.json`, `stage8-rendered/screenshots.json`.
25test mới so với123test GĐ7; tổng148 gồm core52/library44/playback35/app17.
Fixture3lỗi đã sửa, mọi assertion chức năng giữ nguyên; SCREEN_OFF còn thêm
assertion trạng thái OS và sự kiện đã phát. Không sửa production để test qua.

Mean render host: cassette20.01ms/khung; đĩa than12.69ms/khung,
60mẫu/chế độ, đo System.nanoTime thực; gồm idle/sync/bitmap/Skia, bỏ hash/PNG.
Chỉ là mô phỏng máy chủ; không CPU%, không suy pin/CPU/FPS điện thoại.
Ảnh thực tế: [Cassette](../qa/stage8-rendered/cassette-tpsl2.png),
[Đĩa than](../qa/stage8-rendered/vinyl-wm1z.png); đủ8ảnh trong `qa/stage8-rendered/`.
Đây là scene thành phần400×480, không phải toàn màn Đang phát hoặc máy thật.
Tệp tạo/sửa liệt kê tại [stage8-files.txt](stage8-files.txt); toàn bộ code trong commit.
Chữ ký V2/APK SDK/version/permissions/JNI và16KiB đã kiểm; cert trùng bản GĐ7 đã giao.
