# GĐ5 — DSP và đường tín hiệu

Tạo core/DspEngine.kt, playback/{DspState,DspAudioProcessor,StudioRenderersFactory},
app/SoundControls; sửa service rebuild route, SessionCommand DSP, CatalogItems
ReplayGain extras, PlaybackViewModel/Panel. Không thêm dependency; Media3 giữ1.11.1.

EQ peak biquad10 dải31/62/125/250/500/1k/2k/4k/8k/16k ±12dB, Q=sqrt(2).
Clear Bass low shelf80Hz0…12dB; preamp−24…12dB. Công thức RBJ cookbook:
https://webaudio.github.io/Audio-EQ-Cookbook/audio-eq-cookbook.html
State độc lập từng kênh, double coefficient/state, không allocation mỗi sample;
gain/coefficient ramp10ms khi sửa. Band >=Nyquist bỏ qua. Guard ước lượng headroom
trên257 điểm tần số + ReplayGain peak; hard clamp cuối có đếm sample chạm ngưỡng.
Đây không phải limiter lookahead; transient/peak ngoài lưới vẫn có thể bị giới hạn.

ReplayGain track/album và peak đọc từ Room, đưa vào MediaItem extras. Processor
lấy đúng item từ StreamMetadata.timeline/periodUid khi flush ở ranh giới stream;
không áp gain theo event UI chậm. Tag cập nhật muộn được nhận theo ID stream;
không chặn audio thread bằng Room/file IO. Album thiếu thì fallback track.

Media3 DefaultAudioSink bỏ custom chain ở đường float (đã đọc source1.11.1).
Khi DSP bật: cấm compressed passthrough/offload, float output=false, dùng chain
PCM16 của sink; xử lý double rồi xuấtPCM16. Điều này giảm độ sâu nguồn24/32bit;
UI nêu rõ. Source Direct: factory trả mảng app processor rỗng, bật khả năng float
của sink; không EQ/bass/preamp/ReplayGain/guard. Sink vẫn có chuyển đổi cần thiết,
trim gapless và Android vẫn có mixer/resampling; không hứa bit-perfect/USB riêng.
Thiết bị mục tiêu không có USB DAC nên không thêm một công tắc bit-perfect giả.

Đổi Source Direct rebuild renderer/sink, giữ queue/filter/vị trí/trạng thái yêu
cầu phát; có thể ngắt ngắn khi đổi mode, không hứa liền mạch lúc đổi đường DSP.
Audio offload disabled để ưu tiên đường giải mã và gapless; cần máy thật đo pin.
Telemetry dùng Format ở sink.configure và AnalyticsListener AudioTrackConfig,
hiển thị nguồn/PCM giải mã/AudioTrack sampleRate/encoding/channels/offload/decoder.
Không suy tần số DAC hoặc codec Bluetooth từ tần số được yêu cầu của AudioTrack.
Log thêm audio_decoder/audio_track/dsp_config/dsp_route_rebuilt, xuất SAF như GĐ2.

## Build, test, nghiệm thu
`python qa/gate.py 5`; lệnh Gradle đầy đủ trong qa/stage5-gate.json.
Core test sin ở từng center ±12/+6dB, sai số≤0.06dB; ReplayGain≤0.005dB;
Clear Bass10Hz≤0.1dB và4kHz≤0.05dB; channel isolation/stability/Nyquist/headroom.
Processor test chain SourceDirect rỗng, PCM16 exact khi flat/bypass, chia buffer
không trộn channel, ReplayGain theo stream metadata, reject PCM không được chuyển
đổi, config hỏng không đưa NaN vào audio thread. Giữ mọi test GĐ1–4.
Chỉ nghiệm thu host và tag stage5 khi build/test/lint đều0.

## Not verified
Chưa nghe/đo DSP, clipping/pops, precision hoặc latency trên Snapdragon8Gen3;
chưa xác nhận AudioTrack thực tế của loa/BT/tai nghe Xiaomi. Stream-boundary test
là fixture timeline, chưa chứng minh gapless hai file thật có ReplayGain khác nhau.
Không có phép đo DAC/bit-perfect, USB exclusive, Bluetooth codec hoặc tần số HAL.

## Cách tôi tự kiểm trên máy thật
Phát sine/nhạc ở mức âm lượng thấp, thử từng band/ClearBass/Preamp, SourceDirect
phải bỏ toàn bộ thay đổi. Đổi direct khi phát/paused/tua, queue/vị trí phải giữ.
Dùng hai bài cùng PCM nhưng tag−6dB/0dB, album gain khác track gain; nghe chuyển
liên tục. Đối chiếu bảng tín hiệu với nguồn44.1/48/88.2/96kHz, đổi loa/BT/tai nghe
và xuất log; không dùng số AudioTrack để kết luận tần số DAC. Bật guard/giảm preamp
nếu báo sample chạm ngưỡng. Khóa máy15phút với DSP bật rồi kiểm focus/log.

## Gate đã chạy
Build debug,104/104 test và lint app/library/playback trả0. Không bỏ qua test.
Kết quả: qa/stage5-gate.json và qa/stage5-test-results.json; đây là host/Robolectric,
không phải đo AudioTrack/DAC trên điện thoại.
