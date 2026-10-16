// Máy giả để thử dashboard trên Firebase emulator, không cần điện thoại.
// Làm đúng những gì app Android làm: đăng nhập ẩn danh, xin ghép nối, chờ duyệt,
// rồi gửi tín hiệu, giờ dùng, và (tuỳ chọn) xin thêm giờ.
//
//   node tools/fake-device.mjs <MÃ_GIA_ĐÌNH> "<Tên bé>" ["<Tên máy>"] [--ask-extension]
import { initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth, signInAnonymously } from "firebase/auth";
import { connectDatabaseEmulator, getDatabase, onValue, push, ref, serverTimestamp, set, update } from "firebase/database";

const [familyId, childName, model = "Máy giả", ...flags] = process.argv.slice(2);
if (!familyId || !childName) {
  console.error('Cách dùng: node tools/fake-device.mjs <MÃ_GIA_ĐÌNH> "<Tên bé>" ["<Tên máy>"] [--ask-extension]');
  process.exit(1);
}
const app = initializeApp({ apiKey: "demo-api-key", projectId: "demo-sobengoan", databaseURL: "https://demo-sobengoan-default-rtdb.firebaseio.com" }, model);
const auth = getAuth(app);
connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
const db = getDatabase(app);
connectDatabaseEmulator(db, "127.0.0.1", 9000);

const { user } = await signInAnonymously(auth);
const uid = user.uid;
const fam = path => ref(db, `families/${familyId}/${path}`);
const pad = n => String(n).padStart(2, "0");
const d = new Date();
const today = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

console.log(`Máy ${uid} xin ghép nối vào ${familyId}…`);
await set(fam(`pairing/${uid}`), { childName, model, requestedAt: serverTimestamp() });

onValue(fam(`devices/${uid}`), async snap => {
  if (!snap.exists()) return;
  console.log("Đã được duyệt:", snap.val());
  await update(fam(""), {
    [`deviceState/${uid}`]: {
      lastSeen: serverTimestamp(), model, androidVersion: "14", battery: 76, appVersion: "giả",
      accessibilityOn: true, usageAccessOn: true, todayMinutes: 37,
      currentApp: { pkg: "com.google.android.youtube", at: serverTimestamp() },
    },
    [`usage/${uid}/${today}`]: { "com,google,android,youtube": 25, "com,zing,zalo": 12 },
    [`labels/${uid}`]: { "com,google,android,youtube": "YouTube", "com,zing,zalo": "Zalo" },
  });
  if (flags.includes("--ask-extension")) {
    await push(fam(`requests/${uid}`), { type: "extension", status: "pending", at: serverTimestamp() });
    console.log("Đã xin thêm giờ.");
  }
  console.log("Đã gửi dữ liệu. Ctrl+C để thoát.");
}, err => console.error("Lỗi đọc devices:", err.message));
