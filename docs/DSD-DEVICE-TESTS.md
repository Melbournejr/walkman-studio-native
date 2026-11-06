# Bộ kiểm tra DSD trên Xiaomi14Pro — trước GĐ6

Không dùng kết quả host để nghiệm thu Snapdragon8Gen3/Android16/HyperOS.
Giữ một bản WAV/FLAC PCM88.2kHz tham chiếu được chuyển từ cùng master.

| File thật | Phép thử |
|---|---|
| DSF64/128/256 stereo, tag+bìa | Mỗi rate nghe≥2phút; title/artist giữ đúng, sau giải mãPCM88.2kHz |
| DSF mono, LSB và MSB (nếu có) | Hai tai/loa không bị đảo hoặc lẫn channel; không lệch pitch |
| DFF DSD không nén64/128/256, mono/stereo | Nghe đầu/giữa/cuối; đối chiếu PCM tham chiếu |
| DSF/DFF gần500MB | Mở, tua10%/50%/90%/cuối rồi tua lùi; RAM không tăng theo kích thước tệp |
| Cặp DSD cùng định dạng nối liền | Kiểm tra gap/khựng tại ranh giới; log nếu khác PCM |
| DST DFF, DSD512, SACD ISO | Báo chưa hỗ trợ, giữ tên/nghệ sĩ; không gọi file hỏng |
| Nguồn đã thu hồi quyền / bị chuyển / cắt ngắn | Thử lại/Chi tiết, cấp lại SAF; không tự xóa chỉ mục |

Lặp các thử nghe/tua với SourceDirect rồi DSP bật, âm lượng thấp. Tắt màn hình,
vuốt app khỏi đa nhiệm; thử loa/BT/tai nghe, cuộc gọi và rút tai nghe. Không yêu
cầu USB/DoP/nativeDSD; SourceDirect vẫn giải mã DSD sangPCM.

Xuất log: Cài đặt → Xuất log trình phát → chọn tên và vị trí bằng SAF. Sau lỗi
hoặc kill, mở lại app rồi xuất để có historical exit khi Android cung cấp. Ghi
kèm loại file/rate/kích thước, thời điểm/tua tới đâu, đầu ra, trạng thái HyperOS.
Log là JSONL; sự kiện dsd_open / dsd_stats sẽ ghi rate/channels/frames, thời gian
FIR và thời gian xử lý window/IO. Không upload tự động.

Benchmark điện thoại: chạy từng DSD64/128/256 đủ dài, dừng/chuyển bài để chốt
thống kê, xuất log; tính RTF=thời gian xử lý / (frames/88200), mục tiêu<0.25.
Đo trên máy thật phải ghi rõ SoC/OS/nhiệt/đầu ra/SourceDirect và có lặp≥3lần.
Con số chạy trên Xeon/x86_64 chỉ là mô phỏng, không quy đổi thành Snapdragon.

Ngưỡng host: sin1kHz−12dBFS, bỏ transient FIR: sai số amplitude≤0.1dB, tần số
≤0.1Hz; LUT float32 so với cùng FIR float64 maxabs≤1e−5FS; seek ra cùng mẫu với
đọc tuyến tính, vị trí lượng tử hóa≤1frame PCM88.2kHz (~11.34µs). Không thay các
ngưỡng này để test qua. Các ngưỡng không chứng minh chất lượng DAC/codecBluetooth.
