# GĐ6 — Native DSD, seek và kiểm chứng

Tạo core/DsdFormat, fixture/test; playback/cpp/{dsd_fir,dsd_jni,dsd_host_test},
CMakeLists, dsd/{NativeDsdFir,DsdExtractor}; sửa MediaSourceFactory, signal/error
mapping, NDK ABI/build/JNI keep rules; qa/native-host.py và gate. Core testFixtures
là plugin tích hợp Gradle8.13, không dependency bên ngoài. NDK30.0.16248370 (r30),
CMake3.22.1, C++17, -O3 -ffp-contract=off, link max-page-size16384; arm64-v8a/x86_64.

DSF/DFF DSD không nén mono/stereo64/128/256 thuộc họ44.1kHz. C++ Kaiser beta8.6,
taps=ratio*32, cutoff=.32/ratio, normalize DC, LUTfloat32 theo byte, sumdouble,
PCMfloat32 interleaved88.2kHz. Giữ thuật toán bản GPL cũ, byte silence0x99; giữ
nguồn/hash/copyright trong third_party/legacy-dsd và assets licenses/NOTICE.txt.
Không có FFmpeg, WebView/WASM hoặc tải mạng. DST/DSD512/SACDISO/DoP/nativeDSD chưa hỗ trợ.

Custom Extractor parse header theo readAt/cached small header; skip chunk bằng
position, không đọc payload khi parse. DSF channel blocks, DFF interleavedMSB,
DSF LSB/MSB, mask bit padding cuối. Window centered FIR gồm history/lookahead;
seek tính từ frame88.2kHz, lượng tử hóa tối đa1frame(~11.34µs), không resetFIR
rồi bỏ history. Cache physical16×64KiB=1MiB, window2048frame; buffer không theo
kích thước file. Bộ lọc/tables cache vài MiB. Các lần read≤64KiB, dưới trần32MiB.
SAF vẫn dùng PFD→FileChannel; nguồn pipe dùng reopen+skip đã test ở GĐ1, seek có
thể chậm. Media3 load control target8MiB và time/size bounds; không tải cả500MB.

dsd_open/dsd_stats ghi rate/channels/frames, FIRms, windowms gồmIO/seek khi xử lý
window, RTF tính trên lượng PCM đã giải mã; model/SDK/ABI do service ghi lúc chạy.
Không tính thời gian chờ giữa các window do backpressure là thời gian giải mã.
Source Direct vẫn phải giải mã DSD→PCM; AudioTrack/HAL có thể đổi sample rate.
Chi tiết lỗi UI hiện cả lý do unsupported từ parser, giữ tên/nghệ sĩ.

## Build, test và tiêu chí nghiệm thu
`python qa/gate.py 6`; `:playback:hostNative` cần Linux/WSL, g++/Python3/JDK21,
sau đó build Android và test/lint theo JSON. Host JNI .so được build từ cùng
C++ và chọn qua java.library.path của task test; production không có test branch.
C++ reference dùng BesselI0 libstdc++ độc lập với implementation series/LUT.
Sin1kHz−12dBFS: amplitude≤0.1dB, frequency≤0.1Hz sau transient; float64 cùng FIR
maxabs≤1e−5FS. Không đổi ngưỡng. Test cả bit orders, buffer split/seek equivalence.
Robolectric dùng JNI thật trên host: DSF/DFF cảrate/channel, block17/4096, seek
0/1/4095/4096/cuối đúng PCM tuyến tính, partial read97byte, sparse500MB đọc<2MiB,
bit padding, truncated/overflow/DST/DSD512/surround, JNI bounds/close.

Benchmark định lượng trong qa/dsd-host-benchmark.json: mô phỏng x86_64 Linux,
g++14.2 -O3, sine tổng hợp2giây stereo; IO không nằm trong timing,3sample/rate (median và từng mẫu).
Đọc số thực tế trong JSON của commit này; không quy đổi thành số Snapdragon.
Mục tiêu RTF<0.25 trên Snapdragon8Gen3 vẫn phải đo≥3lần trên máy thật.

## Not verified
Chưa chạy JNI Android hoặc nghe file DSD thật trên Xiaomi/Android16; cross-compile
arm64/x86_64 không phải chạy trên điện thoại. Chưa đo SnapdragonRTF/nhiệt/pin,
RAM hệ thống/SAF thật, gaplessDSD, seeklatency thực, DAC/BT. Chưa có FFmpegNDK
extension cho container/codec lạ; chỉ decoder Media3/MediaCodec + DSD custom này.
Kotlin header limits512KiB metadata structure và10000chunks nhằm chặn nguồn bất
thường, không phải giới hạn kích thước file hoặc số bài. DFF yêu cầu PROP/CMPR
DSD trước data; file khác thứ tự cần mẫu để mở rộng parser.

## Cách tôi tự kiểm trên máy thật
Xem docs/DSD-DEVICE-TESTS.md (đã đưa trước khi viết GĐ6). Xuất log tại Cài đặt →
Xuất log trình phát sau khi dừng/chuyển bài để có dsd_stats. Đối chiếu PCM cùng
master, tua gần cuối500MB, SourceDirect/DSP/BT và phát nền. File chưa hỗ trợ phải
báo lý do trong Chi tiết, không coi đó là bằng chứng file hỏng.

## Gate đã chạy
Build debug/NDK2ABI,119/119 JVM/Robolectric tests và lint3module trả0;
C++ reference/sin/bitorder/seek benchmark3lượt qua ngưỡng giữ nguyên.
Median host RTF DSD64/128/256≈0.0161/0.0371/0.0806 (không IO),
maxabs float64≈2.37e−8FS, amplitude<0.002dB, frequency<0.001Hz.
Đối chiếu JSON: qa/stage6-gate.json,stage6-test-results.json,dsd-host-benchmark.json.
