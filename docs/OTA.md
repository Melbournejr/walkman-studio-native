# Cập nhật trong ứng dụng

OTA là ngoại lệ mạng được chủ dự án yêu cầu từ GĐ10. Phát nhạc, quét SAF,
metadata, DSP và DSD tiếp tục hoạt động offline. Không gửi URI, tên bài, thư viện
hoặc định danh thiết bị. App không chứa token GitHub.

- Nguồn duy nhất: GitHub Releases của `Melbournejr/walkman-studio-native`.
- Tự động bật mặc định: WorkManager kiểm tra định kỳ 24 giờ, chỉ khi mạng không
  tính phí. Android/Doze/HyperOS có thể trì hoãn; không bảo đảm giờ chạy chính xác.
- Cài đặt cho phép tắt tự động, cho phép mạng tính phí, kiểm tra, hủy và thử lại.
- Tải streaming 64 KiB, giới hạn gói 128 MiB. Gián đoạn sẽ xóa phần chưa xác minh
  và tải lại từ đầu; không nạp APK vào RAM. APK hợp lệ giữ trong thư mục riêng.
- Luồng cài đặt dùng trình cài Android và yêu cầu người dùng xác nhận. Nếu chưa
  cho phép nguồn này, app mở màn “Cài ứng dụng không xác định”. Hủy không xóa gói.
- API33+ cần cho phép thông báo để thấy lời nhắc nền; nếu tắt, gói vẫn có trong
  Cài đặt. Bản105 cần cài bản bổ sung OTA một lần vì bản105 chưa chứa mã cập nhật.

## Giao thức phát hành

Release ổn định (không draft/prerelease) cần APK và asset `update.json`, ví dụ:

```json
{
  "schema": 1,
  "versionCode": 106,
  "versionName": "1.3.0-native-ota",
  "minSdk": 26,
  "bytes": 123456,
  "sha256": "<SHA-256 thực của APK, 64 ký tự hex thường>",
  "url": "https://github.com/Melbournejr/walkman-studio-native/releases/download/stage10/walkman-native-stage10.apk",
  "certificateSha256": "2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2",
  "notes": "Nội dung thay đổi bằng tiếng Việt"
}
```

`bytes`/`sha256` ở ví dụ là chỗ giữ chỗ; workflow phải tính từ APK đã ký, không
ghi số ước lượng. App đối chiếu size/digest với asset GitHub khi có, kiểm hash sau
tải, đọc package/version/minSdk/chứng chỉ từ APK và đối chiếu chứng chỉ bản đang
cài với pin cố định. Lặp lại kiểm tra ngay trước khi trao URI cho trình cài đặt.
Từ chối gói khác applicationId, khóa ký, version không tăng, hash/size sai hoặc
SDK không phù hợp. Manifest JSON không thay thế sự tin cậy của khóa ký APK.

Chỉ HTTPS. URL ban đầu phải là API latest hoặc đường release của repo này;
chuyển hướng chỉ tới các miền CDN GitHub được liệt kê trong `UpdateTrust`.
Provider không exported, chỉ cấp URI đọc cho tệp dưới `files/updates/`.
Không có quyền đọc toàn bộ bộ nhớ. `allowBackup=false`, cleartext vẫn bị tắt.

## Not verified

Chưa kiểm trên Xiaomi14Pro/Android16: chạy nền qua Doze/HyperOS, thông báo khi
đổi quyền, màn cấp nguồn cài đặt, cài đè và phát nhạc khi mở trình cài. Adapter
PackageManager API26/28/33+ cần kiểm bằng APK thật trên thiết bị; test runner dùng
archive-reader giả ở ranh giới hệ điều hành, không chứng minh cài đặt thực.

## Cách tôi tự kiểm trên máy thật

1. Cài bản OTA đầu tiên; mở Cài đặt → Cập nhật ứng dụng và bật thông báo.
2. Khi có release versionCode cao hơn kèm `update.json`, nối Wi-Fi và bấm kiểm
   tra. Xem tiến độ, hủy, thử lại; tắt Wi-Fi để kiểm trường hợp gián đoạn.
3. Sau khi sẵn sàng, mở quyền cài từ Walkman Studio Native, xác nhận cài đè.
   Kiểm thư viện, URI SAF và lựa chọn theme còn giữ nguyên.
4. Hủy màn cài: trở về app, gói vẫn sẵn sàng. Tắt thông báo: trạng thái vẫn có
   trong Cài đặt. Kiểm tra riêng hoạt động nền sau khi khóa máy và qua HyperOS.
5. Bản hiện hành không tải lại chính nó; cần release mới hơn để thử OTA thực.

Tài liệu Android: [WorkManager và constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work),
[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider),
[quyền nguồn cài đặt](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()),
[PackageInstaller/user action](https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_PENDING_USER_ACTION).
Không suy hành vi thiết bị từ Robolectric hoặc chỉ từ kiểm tra compile/lint.
