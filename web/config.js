// Cấu hình Firebase của dashboard.
//
// Khi đã tạo project thật trên Firebase Console: vào Project settings › Your apps ›
// Web app, chép các giá trị vào FIREBASE_CONFIG bên dưới (thay cấu hình "demo-").
export const FIREBASE_CONFIG = {
  apiKey: "demo-api-key",
  authDomain: "demo-sobengoan.firebaseapp.com",
  databaseURL: "https://demo-sobengoan-default-rtdb.firebaseio.com",
  projectId: "demo-sobengoan",
  appId: "demo-app-id",
};

// Project "demo-..." chỉ chạy được với Firebase emulator trên máy tính
// (npm run emulators). Thêm ?emulator=1 vào địa chỉ để ép dùng emulator.
export const USE_EMULATOR =
  FIREBASE_CONFIG.projectId.startsWith("demo-") ||
  new URLSearchParams(location.search).has("emulator");
