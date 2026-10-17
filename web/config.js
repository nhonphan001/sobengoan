// Cấu hình Firebase của dashboard.
//
// Project thật: so-be-ngoan-3 (Project settings › Your apps › Web app "Dashboard").
// Các giá trị này không phải mật khẩu — trang web nào dùng Firebase cũng để lộ
// chúng; dữ liệu được bảo vệ bằng database.rules.json.
const REAL_CONFIG = {
  apiKey: "AIzaSyA9zU8ivi1PIUzBDXu5YizRqoH8lGQ3lDU",
  authDomain: "so-be-ngoan-3.firebaseapp.com",
  databaseURL: "https://so-be-ngoan-3-default-rtdb.asia-southeast1.firebasedatabase.app",
  projectId: "so-be-ngoan-3",
  storageBucket: "so-be-ngoan-3.firebasestorage.app",
  messagingSenderId: "411272117350",
  appId: "1:411272117350:web:9edf9c57391b3be89d01a7",
};

// Project "demo-" chỉ tồn tại trên Firebase emulator chạy ở máy tính
// (npm run emulators). Thêm ?emulator=1 vào địa chỉ trang để dùng nó.
const DEMO_CONFIG = {
  apiKey: "demo-api-key",
  authDomain: "demo-sobengoan.firebaseapp.com",
  databaseURL: "https://demo-sobengoan-default-rtdb.firebaseio.com",
  projectId: "demo-sobengoan",
  appId: "demo-app-id",
};

export const USE_EMULATOR = new URLSearchParams(location.search).has("emulator");
export const FIREBASE_CONFIG = USE_EMULATOR ? DEMO_CONFIG : REAL_CONFIG;
