# GĐ9c — chẩn đoán fixture hoạt ảnh

## Kết luận có bằng chứng: (b), snapshot/clock của fixture

Không sửa MechanicalTransport hoặc thông số tween/spring để test qua.
Không tăng mốc160ms hoặc đổi assertion1,0±0,01. Không đổi global scale thành1:
run đối chứng đã pass ở scale0, vì nhánh MechanicalTransport trong test nhận
animationsEnabled=true trực tiếp. Production screen vẫn đọc reduced-motion thật.

| Thí nghiệm | Kết quả |
|---|---|
| Fixture gốc: đổi requested, một advance160, không publish snapshot | Expected1, actual0 — XML gốc giữ trong prior-attempt |
| Thêm trace mỗi16ms, vẫn160ms, scale0, cùng production | Target1 nhận tại32ms; animation tăng và đạt1 tại160ms |
| Run đối chứng: sendApplyNotifications trước một advance160, scale0 | Target1, animation1 tại160; test pass |
| Pause + reducedmotion, publish snapshot +1frame | Requestedfalse, renderedLatchfalse, target0, value0 |
| Play + reducedmotion, publish snapshot +1frame | Requestedtrue, renderedLatchtrue, target1, value1 |

Trace từng frame ở scale0 (rawPlayerState=READY=3):

| Clock ms | Input playWhenReady | Input đã compose | Target thực từ latch | Animation |
|---|---|---|---|---|
| 0 | false | false | 0 | 0 |
| 16 | true | false | 0 | 0 |
| 32 | true | true | 1 | 0 |
| 48 | true | true | 1 | 0 |
| 64 | true | true | 1 | 0,077060 |
| 80 | true | true | 1 | 0,421749 |
| 96 | true | true | 1 | 0,749087 |
| 112 | true | true | 1 | 0,904561 |
| 128 | true | true | 1 | 0,975268 |
| 144 | true | true | 1 | 0,999140 |
| 160 | true | true | 1 | 1 |

Không có pointer đang nhấn trong thí nghiệm; target được tính từ latch thực
bằng đúng CassetteKeyModel.targetDepression. Trace còn ghi requested phía test,
input đã compose, rawstate, latch, giá trị animation và ValueAnimator scale.

Run trace từngframe đi qua assertion160 rồi gặp latchpause cũ ở assertion tiếp
trong cùng testfixture. Đây là bằng chứng thứ hai về snapshot chưa publish, không
bị giấu: XML giữ trong animation-diagnostics/scale0-stepped160.xml. Sau đó sửa
fixture bằng Snapshot.sendApplyNotifications trong runOnIdle trước từng advance;
run đối chứng một advance160 và các assertion snap đều pass. Chưa tăng thời gian.

Loại (a): scale0 không chặn animateFloatAsState trong run trực tiếp này — giá trị
đã tăng qua nhiều frame. Loại (c): renderedLatchtrue/target1 chứng minh input phát
đã đi vào phím. Compose1.9.4 dùng MotionDurationScale trong coroutine context;
ValueAnimator static không phải cổng trực tiếp của composable ở fixture này.
Với autoAdvancefalse, idle không tự bơm pending snapshot như lúc autoAdvance.

## Bằng chứng

`qa/stage9c-review/animation-diagnostics/` chứa JSON, XML và log của hai thí nghiệm,
conclusion.json và SHA-256 production MechanicalTransport để đối chiếu bất biến.
Đây là test trên host Robolectric, không chứng minh timing/haptic trên Xiaomi.
Kết quả gate toàn bộ và hình ảnh xem FINAL-REPORT mới nhất; tài liệu này chỉ chốt
chẩn đoán test cụ thể, không thay cho gate.
