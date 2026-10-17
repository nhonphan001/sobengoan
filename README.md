# Sổ Bé Ngoan

Ba mẹ đặt giới hạn giờ dùng máy, chặn ứng dụng, nhắn tin và thưởng điểm cho con.

- `web/` — trang web cho ba mẹ (HTML + JavaScript, không cần build). Đăng lên GitHub Pages.
- `android/` — app Android cài trên máy của bé (Kotlin).
- `database.rules.json` — luật bảo mật Firebase Realtime Database.
- `tests/rules.test.js` — test luật bảo mật trên Firebase emulator.
- `tools/fake-device.mjs` — "máy giả" để thử trang web mà không cần điện thoại.

## Cách hoạt động

1. Ba mẹ đăng ký trên trang web, nhận **Mã gia đình** 6 ký tự.
2. Trên máy của bé: mở app, nhập Mã gia đình và tên bé, rồi gửi yêu cầu ghép nối.
3. Trang web hiện "Máy mới xin ghép nối". Ba mẹ bấm **Duyệt** và chọn máy này là của bé nào (hoặc thêm bé mới).
4. Trên máy của bé: bật **Trợ năng** và **Truy cập dữ liệu sử dụng** cho Sổ Bé Ngoan (app tự hướng dẫn).

Một bé có thể có nhiều máy. Mỗi máy có giới hạn riêng (giờ/ngày, app bị chặn, giới hạn
từng app), online riêng, ảnh chụp màn hình và vị trí riêng. Điểm thưởng, thời khoá biểu và
tin nhắn thì chung cho bé. Ba mẹ đổi tên, chuyển máy sang bé khác, hoặc xoá máy được. Máy bị
xoá thì không còn bị giới hạn và app tự quay về màn hình ghép nối.

### Dữ liệu trên Firebase

```
users/{uid}/familyId
families/{mã gia đình}/
  owner                        uid của ba mẹ
  pairing/{máy}                yêu cầu ghép nối đang chờ
  devices/{máy}                máy đã duyệt: thuộc bé nào, tên máy
  deviceRules/{máy}            giới hạn của máy
  deviceState/{máy}            máy tự báo mỗi phút: online, pin, giờ dùng, quyền đã bật chưa
  usage/{máy}/{ngày}           phút dùng từng app
  labels/{máy}                 tên hiển thị của app
  alerts, location, screenshot/{máy}
  commands/{máy}               lệnh cho máy: chụp màn hình, thưởng giờ
  requests/{máy}               bé xin thêm giờ
  children/{bé}                tên, lớp, điểm
  schedule/{bé}, messages/{bé} phần chung của bé
```

`{máy}` là UID đăng nhập ẩn danh của app trên máy đó. Luật bảo mật: chỉ ba mẹ đọc/ghi được
cả gia đình; mỗi máy chỉ đọc được giới hạn của chính nó và phần chung của đúng bé đó, và
chỉ ghi được tình trạng của chính nó.

## Chạy thử trên máy tính (không cần Firebase thật)

Cần Node.js và Java. Cài một lần:

```bash
npm install
```

Bật Firebase emulator (để cửa sổ này chạy):

```bash
npm run emulators
```

Mở trang web: chạy một web server bất kỳ trong thư mục `web/`, ví dụ

```bash
python3 -m http.server 8766 --directory web
```

rồi mở http://localhost:8766. Cấu hình `demo-sobengoan` trong `web/config.js` tự dùng emulator.

Thử với máy giả (thay `MÃ` bằng Mã gia đình hiện trên trang web):

```bash
node tools/fake-device.mjs MÃ "An" "Samsung A15" --ask-extension
```

App Android bản debug với cấu hình `demo-` cũng tự gọi emulator (qua `10.0.2.2`, chỉ dùng
được trên máy ảo Android). Build:

```bash
cd android && ./gradlew assembleDebug
```

File APK nằm ở `android/app/build/outputs/apk/debug/app-debug.apk`.

Test luật bảo mật:

```bash
npm run test:rules
```

## Đưa lên Firebase thật

1. Vào https://console.firebase.google.com, tạo project mới (ví dụ `so-be-ngoan-3`).
2. **Authentication** › Sign-in method: bật **Email/Password** và **Anonymous**.
3. **Realtime Database** › Create database (chọn Singapore `asia-southeast1`), chế độ khoá (locked mode).
4. Dán nội dung `database.rules.json` vào tab **Rules** rồi Publish. Hoặc dùng Firebase CLI:
   `npx firebase login`, `npx firebase deploy --only database --project <id-project>`.
5. **Project settings** › Your apps:
   - Thêm **Web app**, chép cấu hình vào `web/config.js`.
   - Thêm **Android app** với package `vn.ybfit.sobengoan`, tải `google-services.json`, rồi chép
     các giá trị vào `android/gradle.properties`:
     - `sobengoan.firebase.projectId` = `project_info.project_id`
     - `sobengoan.firebase.apiKey` = `client[0].api_key[0].current_key`
     - `sobengoan.firebase.appId` = `client[0].client_info.mobilesdk_app_id`
     - `sobengoan.firebase.databaseUrl` = `project_info.firebase_url`
6. Build lại app và đăng lại trang web.

## Cài app lên máy của bé

Bật "Cài ứng dụng không rõ nguồn gốc" cho trình quản lý tệp, mở file APK để cài. Trên
Android 13 trở lên, app cài từ file APK bị khoá Trợ năng lúc đầu: vào Cài đặt › Ứng dụng ›
Sổ Bé Ngoan › ⋮ › **Cho phép cài đặt bị hạn chế**, rồi mới bật được Trợ năng.

Khi cập nhật app, cài đè lên bản cũ (đừng gỡ ra): gỡ app thì máy mất UID và phải ghép nối lại.
Sau khi cài đè, kiểm tra lại Trợ năng: có máy tự tắt Trợ năng của app khi cập nhật. Trang web
của ba mẹ sẽ báo "Máy chưa bật Trợ năng" khi bé mở app mà Trợ năng đang tắt.
