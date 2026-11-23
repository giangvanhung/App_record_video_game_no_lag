# GameRec

GameRec ghi màn hình Android trực tiếp vào MP4 bằng encoder phần cứng. Giao diện tiếng Việt, lưu trên thiết bị. Android 8.0 trở lên.

## Cài SDK và build release trên Windows

Với JDK 17 đã cài, chạy từ thư mục dự án:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup-android.ps1
```

Đọc điều khoản và trả lời lời nhắc của `sdkmanager`. Script cài SDK vào `.toolchain/android-sdk`, cấu hình `local.properties`, rồi build và kiểm tra chữ ký APK release. Nếu cần tải ZIP Command-line Tools bằng trình duyệt, tải bản Windows `commandlinetools-win-15859902_latest.zip` từ [Android Developers](https://developer.android.com/studio#command-line-tools-only) và truyền đường dẫn qua tham số `-ToolsZip`.

## Mở bằng Android Studio

1. Mở thư mục chứa `settings.gradle.kts` bằng Android Studio Panda 3 Patch 1 trở lên.
2. Trong SDK Manager, cài **Android SDK Platform 37.0**, **Android SDK Build-Tools 36.0.0** và **Android SDK Platform-Tools**. Đặt Gradle JDK là JDK 17.
3. Chạy **Sync Project with Gradle Files**. Lần đầu cần kết nối để tải Gradle và các thư viện.

Nếu Gradle báo thiếu đường dẫn SDK (`SDK location not found`), mở **Tools → SDK Manager** để xem **Android SDK Location**. Tạo hoặc cập nhật `local.properties` ở thư mục dự án với đường dẫn đó, dùng dấu `/`. Ví dụ cho vị trí mặc định trên Windows:

```properties
sdk.dir=C:/Users/<tên người dùng>/AppData/Local/Android/Sdk
```

## Build và cài APK

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS và Linux:

```sh
sh ./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Bật **Gỡ lỗi USB** trong Tùy chọn nhà phát triển, kết nối điện thoại và xác nhận hộp thoại gỡ lỗi. Cũng có thể chọn thiết bị và bấm **Run** trong Android Studio hoặc mở APK trên điện thoại để cài.

### Build release để cài thử

Trên Windows PowerShell:

```powershell
.\gradlew.bat assembleRelease -PgameRecTestSigning=true
adb install -r app\build\outputs\apk\release\app-release.apk
```

Trên macOS và Linux, dùng `sh ./gradlew assembleRelease -PgameRecTestSigning=true`. Tùy chọn này ký APK release bằng khóa debug của máy build để cài thử. Khi phát hành, chọn **Generate Signed Bundle / APK** trong Android Studio và dùng khóa phát hành riêng.

## Quyền và cách sử dụng

- **Chia sẻ màn hình**. Android yêu cầu xác nhận cho mỗi phiên. Chọn toàn màn hình để chuyển từ GameRec sang game trong cùng phiên hoặc chọn ứng dụng game nếu Android hiển thị lựa chọn này.
- **Thông báo** trên Android 13+. Cấp quyền để sử dụng nút **Dừng ghi** từ ngăn thông báo.
- **Cửa sổ nổi**. Mở **Cài đặt → Cài đặt quyền cửa sổ nổi** để dùng bộ đếm và các nút tạm dừng, tiếp tục, dừng trên game. Khi ghi toàn màn hình, Android có thể ghi cả cửa sổ nổi.
- **Micro**. Cấp khi bật **Ghi micro**. Tính năng này cần encoder AAC phần cứng tương thích và chỉ ghi âm thanh từ micro.
- **Lưu trữ** trên Android 8–9. Cấp để ghi vào thư mục dùng chung. Từ Android 10, GameRec dùng MediaStore để quản lý video do ứng dụng tạo.

## Chất lượng và vòng đời bản ghi

- Mặc định **720p / 30 FPS / 8 Mbps**. Hai mức còn lại là **1080p / 30 FPS / 16 Mbps** và **1080p / 60 FPS / 24 Mbps**. Chọn H.265 nếu thiết bị có encoder phần cứng. Trên Android 8–9, GameRec chỉ thử những tên encoder của nhà sản xuất đã biết vì hệ điều hành chưa công bố cờ phần cứng.
- Có thể chọn lưu vào `Movies/GameRec`, `Movies/GameRec/Gameplay` hoặc `Movies`. Thư viện hiển thị video của GameRec và hỗ trợ phát, chia sẻ, đổi tên, xóa.
- Bật **Khung hình ngang cho game** nếu bắt đầu ghi từ giao diện dọc để chơi game ngang. Mỗi MP4 dùng kích thước khung hình cố định. Khi xoay hoặc thay đổi kích thước nội dung chia sẻ, hệ thống có thể co nội dung và thêm viền.
- Khi bật chế độ tự giảm chất lượng, GameRec chọn 720p nếu pin dưới 20%, Android báo nhiệt nghiêm trọng hoặc nhiệt độ pin ước lượng từ 45 °C. Nếu điều kiện này xuất hiện khi đang ghi 1080p, GameRec chốt tệp hiện tại rồi tiếp tục ở 720p trong tệp mới. Giới hạn thời lượng cộng dồn qua các tệp và bỏ qua thời gian tạm dừng.
- Khi khóa màn hình, thu hồi quyền, dung lượng còn dưới 64 MiB hoặc encoder lỗi, GameRec cố gắng hoàn tất MP4. Nếu hệ điều hành giết tiến trình đột ngột, đoạn đang mở có thể bị mất. GameRec dọn mục đang ghi dở khi mở lại. Các tệp đã chốt có thể phát bình thường.

## Kiểm tra trên thiết bị thật

1. Ghi mặc định, chuyển sang game, tạm dừng/tiếp tục rồi dừng bằng thông báo hoặc cửa sổ nổi. Phát lại để kiểm tra đoạn tạm dừng.
2. Thử ghi micro, H.265 và 1080p/60 FPS trên máy có encoder tương thích. Kiểm tra hình, tiếng và độ mượt trong cùng một cảnh game.
3. Kiểm tra xoay màn hình, khóa màn hình và dừng chia sẻ từ giao diện Android. Xác nhận video đã chốt xuất hiện trong thư viện.
4. Dùng thiết bị thử nghiệm để kiểm tra giới hạn dung lượng, chuyển 1080p xuống 720p khi pin yếu, và các thao tác đổi tên, chia sẻ, xóa.
