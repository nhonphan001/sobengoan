// Dashboard phụ huynh của Sổ Bé Ngoan.
//
// Dữ liệu trên Firebase Realtime Database (xem database.rules.json):
//   users/{uid}/familyId
//   families/{familyId}/
//     owner                         uid của phụ huynh
//     pairing/{deviceUid}           máy đang xin ghép nối {childName, model, requestedAt}
//     devices/{deviceUid}           máy đã duyệt {childId, name, addedAt}
//     deviceRules/{deviceUid}       giới hạn của máy {dailyLimit, blocked, appLimits, bonus/{ngày}, locationEnabled}
//     deviceState/{deviceUid}       máy tự báo {lastSeen, todayMinutes, currentApp, model, battery, quyền...}
//     usage/{deviceUid}/{ngày}      phút dùng từng app hôm đó (khoá = tên gói, "." thay bằng ",")
//     labels/{deviceUid}            tên hiển thị của từng app
//     alerts, location, screenshot/{deviceUid}
//     commands/{deviceUid}          lệnh cho máy {screenshotAt, bonus}
//     requests/{deviceUid}/{id}     bé xin thêm giờ {type, status, at}
//     children/{childId}            {name, grade, points}
//     schedule/{childId}, messages/{childId}   phần chung của bé, mọi máy của bé đều thấy

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import {
  connectAuthEmulator, createUserWithEmailAndPassword, getAuth, onAuthStateChanged,
  signInWithEmailAndPassword, signOut,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import {
  connectDatabaseEmulator, get, getDatabase, limitToLast, onValue, push, query, ref,
  runTransaction, serverTimestamp, set, update,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-database.js";
import { FIREBASE_CONFIG, USE_EMULATOR } from "./config.js";

const fbApp = initializeApp(FIREBASE_CONFIG);
const auth = getAuth(fbApp);
const db = getDatabase(fbApp);
if (USE_EMULATOR) {
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectDatabaseEmulator(db, "127.0.0.1", 9000);
}

// ---------- Tiện ích ----------
const $ = id => document.getElementById(id);
const ONLINE_MS = 3 * 60 * 1000; // máy báo tín hiệu mỗi phút; 3 phút không thấy = offline
const DEFAULT_DAILY_LIMIT = 120;
const PRESET_APPS = [
  { name: "YouTube", pkg: "com.google.android.youtube" },
  { name: "TikTok", pkg: "com.zhiliaoapp.musically" },
  { name: "Facebook", pkg: "com.facebook.katana" },
  { name: "Messenger", pkg: "com.facebook.orca" },
  { name: "Instagram", pkg: "com.instagram.android" },
  { name: "Zalo", pkg: "com.zing.zalo" },
  { name: "Chrome", pkg: "com.android.chrome" },
  { name: "CH Play", pkg: "com.android.vending" },
];
const DAYS = [["T2", "Thứ 2"], ["T3", "Thứ 3"], ["T4", "Thứ 4"], ["T5", "Thứ 5"], ["T6", "Thứ 6"], ["T7", "Thứ 7"], ["CN", "Chủ nhật"]];

function esc(v) {
  return String(v ?? "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));
}
// Khoá Firebase không được chứa ".", nên tên gói lưu với "," thay cho ".".
const pkgKey = pkg => pkg.replaceAll(".", ",");
const pkgFromKey = key => key.replaceAll(",", ".");
const pad = n => String(n).padStart(2, "0");
function todayKey() {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
function todayDayCode() {
  return ["CN", "T2", "T3", "T4", "T5", "T6", "T7"][new Date().getDay()];
}
function timeAgo(ts) {
  if (!ts) return "chưa có";
  const s = Math.max(0, Math.round((Date.now() - ts) / 1000));
  if (s < 60) return "vừa xong";
  if (s < 3600) return `${Math.floor(s / 60)} phút trước`;
  if (s < 86400) return `${Math.floor(s / 3600)} giờ trước`;
  return `${Math.floor(s / 86400)} ngày trước`;
}
function clock(ts) {
  const d = new Date(ts);
  return `${pad(d.getHours())}:${pad(d.getMinutes())} ${pad(d.getDate())}/${pad(d.getMonth() + 1)}`;
}
const isOnline = ts => !!ts && Date.now() - ts < ONLINE_MS;

let errTimer;
function showError(msg, e) {
  if (e) console.error(msg, e);
  $("errBox").textContent = msg;
  $("errBox").hidden = false;
  clearTimeout(errTimer);
  errTimer = setTimeout(() => { $("errBox").hidden = true; }, 8000);
}
async function attempt(promise, msg) {
  try { return await promise; } catch (e) { showError(msg, e); return null; }
}

// ---------- Trạng thái ----------
const S = {
  familyId: null,
  fam: { children: {}, devices: {}, deviceRules: {}, deviceState: {}, pairing: {}, requests: {} },
  schedule: {}, messages: {},                       // của bé đang xem
  usage: {}, labels: {}, alerts: {}, location: null, screenshot: null, // của máy đang xem
  childId: null, deviceUid: null,
  scheduleDay: todayDayCode(),
  screenshotRequestedAt: null,
};
const famRef = path => ref(db, `families/${S.familyId}${path ? "/" + path : ""}`);
const famUpdate = updates => update(famRef(""), updates);

const subs = {};
function listen(name, q, cb) {
  if (subs[name]) subs[name]();
  subs[name] = onValue(q, snap => { cb(snap.val()); scheduleRender(); },
    e => showError("Mất kết nối tới Firebase — thử tải lại trang.", e));
}
// subs.__child / subs.__device là chuỗi đánh dấu bé/máy đang nghe, không phải hàm huỷ.
function stopListening(prefix = "") {
  for (const name of Object.keys(subs)) {
    if (name.startsWith("__")) { if (!prefix) delete subs[name]; continue; }
    if (name.startsWith(prefix)) { subs[name](); delete subs[name]; }
  }
}

const childIds = () => Object.keys(S.fam.children)
  .sort((a, b) => (S.fam.children[a].createdAt || 0) - (S.fam.children[b].createdAt || 0));
const devicesOf = childId => Object.keys(S.fam.devices)
  .filter(uid => S.fam.devices[uid].childId === childId)
  .sort((a, b) => (S.fam.devices[a].addedAt || 0) - (S.fam.devices[b].addedAt || 0));
const stateOf = uid => S.fam.deviceState[uid] || {};
function rulesOf(uid) {
  const r = S.fam.deviceRules[uid] || {};
  return {
    dailyLimit: r.dailyLimit ?? DEFAULT_DAILY_LIMIT,
    blocked: r.blocked || {},
    appLimits: r.appLimits || {},
    bonusToday: (r.bonus && r.bonus[todayKey()]) || 0,
    locationEnabled: !!r.locationEnabled,
  };
}
const deviceLabel = uid => (S.fam.devices[uid] && S.fam.devices[uid].name) || stateOf(uid).model || "Thiết bị";
function appName(pkg) {
  const preset = PRESET_APPS.find(a => a.pkg === pkg);
  if (preset) return preset.name;
  const label = S.labels[pkgKey(pkg)];
  if (label) return label;
  const last = pkg.split(".").pop() || pkg;
  return last.charAt(0).toUpperCase() + last.slice(1);
}
function pendingRequests(uid) {
  return Object.entries(S.fam.requests[uid] || {})
    .filter(([, r]) => r.status === "pending")
    .sort((a, b) => b[1].at - a[1].at);
}

// ---------- Đăng nhập ----------
let authMode = "login";
let idType = "phone";

function phoneToEmail(raw) {
  let digits = raw.replace(/\D/g, "");
  if (digits.length === 11 && digits.startsWith("84")) digits = "0" + digits.slice(2);
  return `sdt${digits}@sobengoan.app`;
}
function authErrorText(code) {
  const who = idType === "phone" ? "Số điện thoại" : "Email";
  if (code === "auth/email-already-in-use") return `${who} này đã đăng ký rồi — thử đăng nhập.`;
  if (["auth/invalid-credential", "auth/wrong-password", "auth/user-not-found", "auth/invalid-login-credentials"].includes(code))
    return `Sai ${who.toLowerCase()} hoặc mật khẩu.`;
  if (code === "auth/weak-password") return "Mật khẩu quá ngắn (tối thiểu 6 ký tự).";
  if (code === "auth/invalid-email") return idType === "phone" ? "Số điện thoại không hợp lệ." : "Email không hợp lệ.";
  if (code === "auth/too-many-requests") return "Thử sai quá nhiều lần, đợi một lúc rồi thử lại.";
  if (code === "auth/network-request-failed") return "Không có mạng — kiểm tra kết nối rồi thử lại.";
  return `Có lỗi xảy ra, thử lại sau. (${code})`;
}
function renderAuthMode() {
  const register = authMode === "register";
  $("authSubmit").textContent = register ? "Đăng ký" : "Đăng nhập";
  $("authSwitchText").textContent = register ? "Đã có tài khoản?" : "Chưa có tài khoản?";
  $("authSwitchLink").textContent = register ? "Đăng nhập" : "Đăng ký";
  $("authPassword").autocomplete = register ? "new-password" : "current-password";
  document.querySelectorAll(".seg-btn").forEach(b => b.classList.toggle("active", b.dataset.type === idType));
  $("authId").type = idType === "phone" ? "tel" : "email";
  $("authId").placeholder = idType === "phone" ? "Số điện thoại (vd: 0912345678)" : "Email";
}

$("authForm").addEventListener("submit", async e => {
  e.preventDefault();
  const raw = $("authId").value.trim();
  const email = idType === "phone" ? phoneToEmail(raw) : raw;
  const password = $("authPassword").value;
  $("authError").hidden = true;
  $("authSubmit").disabled = true;
  try {
    if (authMode === "register") await createUserWithEmailAndPassword(auth, email, password);
    else await signInWithEmailAndPassword(auth, email, password);
  } catch (err) {
    $("authError").textContent = authErrorText(err.code);
    $("authError").hidden = false;
  } finally {
    $("authSubmit").disabled = false;
  }
});

function randomFamilyCode() {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // bỏ O/0, I/1 dễ nhầm
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return Array.from(bytes, b => chars[b % chars.length]).join("");
}

// Lần đầu đăng nhập: tạo gia đình với mã ngẫu nhiên. Mã đã có chủ thì luật bảo
// mật chặn việc ghi đè, nên chỉ cần thử mã khác.
async function ensureFamily(uid) {
  const existing = await get(ref(db, `users/${uid}/familyId`));
  if (existing.exists()) return existing.val();
  for (let i = 0; i < 5; i++) {
    const code = randomFamilyCode();
    try {
      await set(ref(db, `families/${code}`), { owner: uid, createdAt: serverTimestamp() });
    } catch {
      continue;
    }
    await set(ref(db, `users/${uid}/familyId`), code);
    return code;
  }
  throw new Error("Không tạo được mã gia đình");
}

onAuthStateChanged(auth, async user => {
  stopListening();
  S.familyId = null;
  if (!user) {
    $("loading").hidden = true;
    $("app").hidden = true;
    $("authScreen").hidden = false;
    renderAuthMode();
    return;
  }
  $("authScreen").hidden = true;
  $("loading").hidden = false;
  try {
    S.familyId = await ensureFamily(user.uid);
  } catch (e) {
    console.error(e);
    $("loading").textContent = "Không lấy được Mã gia đình — kiểm tra mạng rồi tải lại trang.";
    return;
  }
  $("familyCode").textContent = S.familyId;
  for (const key of Object.keys(S.fam)) {
    listen(`fam:${key}`, famRef(key), v => { S.fam[key] = v || {}; });
  }
  $("loading").hidden = true;
  $("app").hidden = false;
  scheduleRender();
});

// ---------- Chọn bé / chọn máy ----------
// Đăng ký nghe dữ liệu riêng của bé và của máy đang xem — chỉ máy đang xem mới
// tải giờ dùng theo app, ảnh chụp, vị trí (ảnh chụp khá nặng).
function syncSelection() {
  const ids = childIds();
  if (!ids.includes(S.childId)) S.childId = ids[0] || null;
  const devs = S.childId ? devicesOf(S.childId) : [];
  if (!devs.includes(S.deviceUid)) S.deviceUid = devs[0] || null;

  const childKey = S.childId || "";
  if (subs.__child !== childKey) {
    stopListening("child:");
    S.schedule = {}; S.messages = {};
    if (S.childId) {
      listen("child:schedule", famRef(`schedule/${S.childId}`), v => { S.schedule = v || {}; });
      listen("child:messages", query(famRef(`messages/${S.childId}`), limitToLast(50)), v => { S.messages = v || {}; });
    }
    subs.__child = childKey;
  }
  const deviceKey = S.deviceUid ? `${S.deviceUid}/${todayKey()}` : "";
  if (subs.__device !== deviceKey) {
    stopListening("dev:");
    Object.assign(S, { usage: {}, labels: {}, alerts: {}, location: null, screenshot: null, screenshotRequestedAt: null });
    if (S.deviceUid) {
      const u = S.deviceUid;
      listen("dev:usage", famRef(`usage/${u}/${todayKey()}`), v => { S.usage = v || {}; });
      listen("dev:labels", famRef(`labels/${u}`), v => { S.labels = v || {}; });
      listen("dev:alerts", query(famRef(`alerts/${u}`), limitToLast(10)), v => { S.alerts = v || {}; });
      listen("dev:location", famRef(`location/${u}`), v => { S.location = v; });
      listen("dev:screenshot", famRef(`screenshot/${u}`), v => { S.screenshot = v; });
    }
    subs.__device = deviceKey;
  }
}

// ---------- Hiển thị ----------
let renderQueued = false;
function scheduleRender() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => { renderQueued = false; if (S.familyId) render(); });
}
setInterval(scheduleRender, 30 * 1000); // cập nhật "x phút trước", trạng thái online

function render() {
  syncSelection();
  renderPairing();
  renderRequests();
  renderKids();
  const hasChild = !!S.childId;
  $("emptyFamily").hidden = hasChild || Object.keys(S.fam.pairing).length > 0;
  $("childView").hidden = !hasChild;
  if (!hasChild) return;
  renderDeviceTabs();
  $("deviceView").hidden = !S.deviceUid;
  if (S.deviceUid) renderDevice();
  renderChildShared();
}

function renderPairing() {
  $("pairingList").innerHTML = Object.entries(S.fam.pairing)
    .sort((a, b) => (a[1].requestedAt || 0) - (b[1].requestedAt || 0))
    .map(([uid, p]) => `
      <div class="banner pairing">
        <div>
          <div class="banner-text">📱 Máy mới xin ghép nối: ${esc(p.childName)}</div>
          <div class="muted">${esc(p.model || "Không rõ tên máy")} · ${timeAgo(p.requestedAt)}</div>
        </div>
        <div class="row" style="margin:0">
          <button class="btn primary" data-act="openApprove" data-uid="${esc(uid)}">Duyệt</button>
          <button class="btn" data-act="reject" data-uid="${esc(uid)}">Từ chối</button>
        </div>
      </div>`).join("");
}

function renderRequests() {
  const rows = [];
  for (const childId of childIds()) {
    const devs = devicesOf(childId);
    for (const uid of devs) {
      const reqs = pendingRequests(uid);
      if (!reqs.length) continue;
      const name = esc(S.fam.children[childId].name);
      const who = devs.length > 1 ? `${name} (${esc(deviceLabel(uid))})` : name;
      rows.push(`
        <div class="banner request">
          <div class="banner-text">🙋 ${who} xin thêm giờ · ${timeAgo(reqs[0][1].at)}</div>
          <div class="row" style="margin:0">
            <button class="btn primary" data-act="grant" data-uid="${esc(uid)}" data-min="15">Cho +15 phút</button>
            <button class="btn primary" data-act="grant" data-uid="${esc(uid)}" data-min="30">Cho +30 phút</button>
            <button class="btn" data-act="dismissRequests" data-uid="${esc(uid)}">Bỏ qua</button>
          </div>
        </div>`);
    }
  }
  $("requestList").innerHTML = rows.join("");
}

function renderKids() {
  $("kidTabs").innerHTML = childIds().map(id => {
    const c = S.fam.children[id];
    const devs = devicesOf(id);
    const lastSeen = Math.max(0, ...devs.map(u => stateOf(u).lastSeen || 0));
    const on = isOnline(lastSeen);
    const waiting = devs.some(u => pendingRequests(u).length);
    const status = devs.length === 0 ? "Chưa có máy"
      : on ? "Đang online" : lastSeen ? `Offline · ${timeAgo(lastSeen)}` : "Chưa có tín hiệu";
    return `
      <button class="kid ${id === S.childId ? "active" : ""}" data-act="selectChild" data-id="${esc(id)}">
        <span class="avatar">${esc((c.name || "?").charAt(0).toUpperCase())}${waiting ? '<span class="badge"></span>' : ""}</span>
        <span>
          <div class="kid-name">${esc(c.name)}</div>
          <div class="muted">${c.grade ? "Lớp " + esc(c.grade) + " · " : ""}${devs.length} máy</div>
          <div class="status ${on ? "on" : ""}"><span class="dot"></span>${status}</div>
        </span>
      </button>`;
  }).join("");
}

function renderDeviceTabs() {
  const devs = devicesOf(S.childId);
  $("deviceTabs").innerHTML = devs.length
    ? `<span class="muted">Máy của ${esc(S.fam.children[S.childId].name)}:</span>` + devs.map(uid => {
        const st = stateOf(uid);
        const on = isOnline(st.lastSeen);
        return `<button class="device-tab ${uid === S.deviceUid ? "active" : ""}" data-act="selectDevice" data-uid="${esc(uid)}">
          ${esc(deviceLabel(uid))}<span class="status ${on ? "on" : ""}"><span class="dot"></span>${on ? "online" : timeAgo(st.lastSeen)}</span>
        </button>`;
      }).join("")
    : `<div class="notice">${esc(S.fam.children[S.childId].name)} chưa có máy nào. Cài app trên máy của bé, nhập Mã gia đình rồi bấm Duyệt ở đây.</div>`;
}

function renderDevice() {
  const uid = S.deviceUid;
  const st = stateOf(uid);
  const rules = rulesOf(uid);
  const on = isOnline(st.lastSeen);

  // --- đầu thẻ: tên máy, tình trạng, quyền còn thiếu ---
  const warnings = [];
  if (st.lastSeen && st.accessibilityOn === false)
    warnings.push("Máy chưa bật <b>Trợ năng</b> cho Sổ Bé Ngoan — máy đang KHÔNG bị chặn hay giới hạn.");
  if (st.lastSeen && st.usageAccessOn === false)
    warnings.push("Máy chưa cấp quyền <b>Truy cập dữ liệu sử dụng</b> — không đếm được giờ dùng.");
  if (!st.lastSeen)
    warnings.push("Chưa nhận được tín hiệu nào từ máy này. Mở app Sổ Bé Ngoan trên máy và làm theo hướng dẫn cấp quyền.");
  $("deviceHeader").innerHTML = `
    <div class="device-head">
      <div>
        <div class="device-name">📱 ${esc(deviceLabel(uid))}</div>
        <div class="status ${on ? "on" : ""}"><span class="dot"></span>${on ? "Đang online" : "Offline · tín hiệu cuối " + timeAgo(st.lastSeen)}</div>
      </div>
      <div class="row" style="margin:0">
        <button class="btn tiny" data-act="renameDevice">✏️ Đổi tên</button>
        <button class="btn tiny" data-act="moveDevice">↔ Chuyển sang bé khác</button>
        <button class="btn tiny danger" data-act="removeDevice">🗑 Xoá máy</button>
      </div>
    </div>
    <div class="facts muted">
      ${st.model ? `<span>${esc(st.model)}</span>` : ""}
      ${st.androidVersion ? `<span>Android ${esc(st.androidVersion)}</span>` : ""}
      ${st.battery != null ? `<span>🔋 ${esc(st.battery)}%</span>` : ""}
      ${st.appVersion ? `<span>App ${esc(st.appVersion)}</span>` : ""}
    </div>
    ${warnings.map(w => `<div class="notice">⚠️ ${w}</div>`).join("")}`;

  // --- thời gian dùng ---
  const used = Object.values(S.usage).reduce((s, m) => s + Number(m || 0), 0);
  const limit = rules.dailyLimit + rules.bonusToday;
  const pct = limit > 0 ? Math.min(100, (used / limit) * 100) : 100;
  $("usageSummary").innerHTML = `
    <div class="big">${used} <span class="muted" style="font-size:15px">/ ${limit} phút</span></div>
    <div class="bar"><div class="${used >= limit ? "over" : ""}" style="width:${pct}%"></div></div>
    <div class="muted" style="margin-top:6px">${used >= limit ? "Đã hết giờ hôm nay" : `Còn ${limit - used} phút`}${rules.bonusToday ? ` · gồm +${rules.bonusToday} phút thưởng` : ""}</div>`;

  const ca = st.currentApp;
  const caLive = ca && on && ca.at && Date.now() - ca.at < ONLINE_MS * 5;
  $("currentApp").innerHTML = caLive
    ? `<div class="list-row"><div class="grow"><b>${esc(appName(ca.pkg))}</b><div class="muted">${esc(ca.pkg)} · ${timeAgo(ca.at)}</div></div>
         ${blockButton(ca.pkg, rules)}</div>`
    : `<div class="muted">Không có dữ liệu gần đây (máy đang tắt màn hình hoặc offline).</div>`;

  const apps = Object.entries(S.usage).map(([k, m]) => [pkgFromKey(k), Number(m || 0)])
    .filter(([, m]) => m > 0).sort((a, b) => b[1] - a[1]);
  $("appUsage").innerHTML = apps.length
    ? apps.map(([pkg, m]) => `
        <div class="list-row">
          <div class="grow"><b>${esc(appName(pkg))}</b> <span class="muted">${m} phút${rules.appLimits[pkgKey(pkg)] ? ` / ${rules.appLimits[pkgKey(pkg)]}` : ""}</span>
            <div class="bar"><div style="width:${Math.min(100, (m / Math.max(1, apps[0][1])) * 100)}%"></div></div></div>
          ${blockButton(pkg, rules)}
        </div>`).join("")
    : `<div class="muted">Hôm nay chưa dùng ứng dụng nào.</div>`;

  // --- giới hạn ---
  if (document.activeElement !== $("dailyLimitInput")) $("dailyLimitInput").value = rules.dailyLimit;
  const blockedPkgs = Object.keys(rules.blocked).map(pkgFromKey);
  const chipPkgs = [...PRESET_APPS.map(a => a.pkg), ...blockedPkgs.filter(p => !PRESET_APPS.some(a => a.pkg === p))];
  $("blockedChips").innerHTML = chipPkgs.map(pkg =>
    `<button class="chip ${rules.blocked[pkgKey(pkg)] ? "on" : ""}" data-act="toggleBlock" data-pkg="${esc(pkg)}">${rules.blocked[pkgKey(pkg)] ? "✕ " : ""}${esc(appName(pkg))}</button>`
  ).join("");

  // Không vẽ lại ô nhập khi ba mẹ đang gõ dở
  if (!$("appLimits").contains(document.activeElement)) {
    const limitPkgs = [...new Set([
      ...PRESET_APPS.map(a => a.pkg),
      ...Object.keys(rules.appLimits).map(pkgFromKey),
      ...apps.map(([p]) => p),
    ])];
    $("appLimits").innerHTML = limitPkgs.map(pkg => `
      <div class="list-row">
        <div class="grow">${esc(appName(pkg))} <span class="muted">đã dùng ${Number(S.usage[pkgKey(pkg)] || 0)} phút</span></div>
        <input class="input small app-limit" type="number" min="0" max="1440" step="5" data-pkg="${esc(pkg)}"
          value="${rules.appLimits[pkgKey(pkg)] ?? ""}" placeholder="—">
      </div>`).join("");
  }

  // --- ảnh chụp ---
  const shot = S.screenshot;
  const waiting = S.screenshotRequestedAt && (!shot || shot.at < S.screenshotRequestedAt);
  $("screenshotBtn").disabled = !!waiting;
  $("screenshotBtn").textContent = waiting ? "Đang chờ máy chụp…" : "Chụp màn hình ngay";
  const shotKey = waiting ? "waiting" : shot ? String(shot.at) : "none";
  if ($("screenshotView").dataset.key !== shotKey) {
    $("screenshotView").dataset.key = shotKey;
    $("screenshotView").innerHTML = waiting
      ? `<div class="muted" style="margin-top:8px">Đã gửi yêu cầu. Máy cần đang bật màn hình và có mạng, có thể mất khoảng 1 phút.</div>`
      : shot
        ? `<img class="screenshot" src="data:image/jpeg;base64,${shot.image}" data-act="openLightbox" alt="Ảnh chụp màn hình">
           <div class="muted">Chụp lúc ${clock(shot.at)}</div>`
        : `<div class="muted" style="margin-top:8px">Chưa có ảnh nào. Cần Android 11 trở lên.</div>`;
  }

  // --- vị trí ---
  $("locationToggle").checked = rules.locationEnabled;
  const loc = S.location;
  const locKey = !rules.locationEnabled ? "off" : loc ? `${loc.lat},${loc.lng},${loc.at}` : "wait";
  if ($("locationView").dataset.key !== locKey) {
    $("locationView").dataset.key = locKey;
    $("locationView").innerHTML = !rules.locationEnabled
      ? `<div class="muted">Định vị đang tắt.</div>`
      : loc
        ? `<div class="muted">Cập nhật ${clock(loc.at)}</div>
           <iframe class="map" loading="lazy" title="Bản đồ"
             src="https://www.openstreetmap.org/export/embed.html?bbox=${loc.lng - 0.01}%2C${loc.lat - 0.01}%2C${loc.lng + 0.01}%2C${loc.lat + 0.01}&layer=mapnik&marker=${loc.lat}%2C${loc.lng}"></iframe>
           <a href="https://www.google.com/maps?q=${loc.lat},${loc.lng}" target="_blank" rel="noopener">Mở trong Google Maps →</a>`
        : `<div class="muted">Đang đợi máy gửi vị trí đầu tiên (máy cần cấp quyền vị trí).</div>`;
  }

  // --- cảnh báo ---
  const alertText = a => a.type === "DAILY_LIMIT" ? `Hết giờ dùng máy, đã chặn ${esc(appName(a.pkg))}`
    : a.type === "BLOCKED_APP" ? `Mở ứng dụng bị chặn: ${esc(appName(a.pkg))}`
    : a.type === "APP_LIMIT" ? `Hết giờ riêng của ${esc(appName(a.pkg))}`
    : a.type === "ACCESSIBILITY_OFF" ? "Trợ năng của Sổ Bé Ngoan vừa bị tắt"
    : esc(a.type);
  const alerts = Object.values(S.alerts).sort((a, b) => b.at - a.at);
  $("alertsView").innerHTML = alerts.length
    ? alerts.map(a => `<div class="list-row"><div class="grow">${alertText(a)}</div><span class="muted">${clock(a.at)}</span></div>`).join("")
    : `<div class="muted">Chưa có cảnh báo nào.</div>`;
}

function blockButton(pkg, rules) {
  const on = !!rules.blocked[pkgKey(pkg)];
  return `<button class="btn tiny ${on ? "danger" : ""}" data-act="toggleBlock" data-pkg="${esc(pkg)}">${on ? "✕ Đã chặn" : "Chặn"}</button>`;
}

function renderChildShared() {
  const c = S.fam.children[S.childId];
  $("sharedTitle").textContent = `Chung cho ${c.name} (mọi máy của bé)`;
  $("pointsView").textContent = c.points || 0;
  $("childProfile").innerHTML = `<b>${esc(c.name)}</b>${c.grade ? ` · Lớp ${esc(c.grade)}` : ""}`;

  const today = todayDayCode();
  $("scheduleDays").innerHTML = DAYS.map(([code, label]) =>
    `<button class="chip day ${code === S.scheduleDay ? "on" : ""}" data-act="scheduleDay" data-day="${code}">${label}${code === today ? " • hôm nay" : ""}</button>`
  ).join("");
  const items = Object.entries(S.schedule)
    .filter(([, it]) => it.day === S.scheduleDay || it.day === "daily")
    .sort((a, b) => a[1].time.localeCompare(b[1].time));
  $("scheduleList").innerHTML = items.length
    ? items.map(([id, it]) => `
        <div class="list-row">
          <b>${esc(it.time)}</b>
          <div class="grow">${esc(it.title)}${it.day === "daily" ? ' <span class="muted">(hằng ngày)</span>' : ""}</div>
          <button class="btn tiny" data-act="deleteSchedule" data-id="${esc(id)}">✕</button>
        </div>`).join("")
    : `<div class="muted" style="margin-top:8px">Chưa có hoạt động nào cho ngày này.</div>`;

  const chat = $("chatList");
  const atBottom = chat.scrollHeight - chat.scrollTop - chat.clientHeight < 40;
  const msgs = Object.values(S.messages).sort((a, b) => a.at - b.at);
  chat.innerHTML = msgs.length
    ? msgs.map(m => `<div class="msg ${m.from === "parent" ? "parent" : "child"}" title="${clock(m.at)}">${esc(m.text)}</div>`).join("")
    : `<div class="muted">Chưa có tin nhắn.</div>`;
  if (atBottom) chat.scrollTop = chat.scrollHeight;
}

// ---------- Thao tác ----------
const actions = {
  idType: d => { idType = d.type; renderAuthMode(); },
  toggleAuthMode: () => { authMode = authMode === "login" ? "register" : "login"; renderAuthMode(); },
  logout: () => signOut(auth),

  selectChild: d => { S.childId = d.id; S.deviceUid = null; render(); },
  selectDevice: d => { S.deviceUid = d.uid; render(); },
  scheduleDay: d => { S.scheduleDay = d.day; render(); },

  openApprove: d => openApproveDialog(d.uid),
  approve: d => approveDevice(d.uid, d.child || null),
  closeDialog: () => $("approveDialog").close(),
  reject: async d => {
    const p = S.fam.pairing[d.uid];
    if (!confirm(`Từ chối yêu cầu ghép nối của máy "${p ? p.model || p.childName : ""}"?`)) return;
    await attempt(famUpdate({ [`pairing/${d.uid}`]: null }), "Không từ chối được — kiểm tra mạng.");
  },

  grant: d => grantBonus(d.uid, Number(d.min), true),
  bonus: d => grantBonus(S.deviceUid, Number(d.min), false),
  dismissRequests: d => {
    const updates = {};
    for (const [id] of pendingRequests(d.uid)) updates[`requests/${d.uid}/${id}/status`] = "dismissed";
    return attempt(famUpdate(updates), "Không cập nhật được yêu cầu — kiểm tra mạng.");
  },

  toggleBlock: d => {
    const key = pkgKey(d.pkg);
    const on = !!rulesOf(S.deviceUid).blocked[key];
    return attempt(set(famRef(`deviceRules/${S.deviceUid}/blocked/${key}`), on ? null : true),
      "Không lưu được danh sách chặn — kiểm tra mạng.");
  },
  requestScreenshot: async () => {
    S.screenshotRequestedAt = Date.now();
    scheduleRender();
    const ok = await attempt(set(famRef(`commands/${S.deviceUid}/screenshotAt`), serverTimestamp()),
      "Không gửi được yêu cầu chụp màn hình — kiểm tra mạng.");
    if (ok === null) { S.screenshotRequestedAt = null; scheduleRender(); }
  },
  openLightbox: () => {
    if (!S.screenshot) return;
    $("lightboxImg").src = `data:image/jpeg;base64,${S.screenshot.image}`;
    $("lightbox").showModal();
  },
  closeLightbox: () => $("lightbox").close(),

  renameDevice: () => {
    const name = prompt("Tên của máy này (vd: Điện thoại của An, Máy tính bảng):", deviceLabel(S.deviceUid));
    if (name === null || !name.trim()) return;
    return attempt(set(famRef(`devices/${S.deviceUid}/name`), name.trim().slice(0, 40)), "Không đổi được tên — kiểm tra mạng.");
  },
  moveDevice: () => openMoveDialog(),
  moveTo: d => {
    $("approveDialog").close();
    return attempt(set(famRef(`devices/${S.deviceUid}/childId`), d.child), "Không chuyển được máy — kiểm tra mạng.")
      .then(() => { S.childId = d.child; render(); });
  },
  removeDevice: async () => {
    const uid = S.deviceUid;
    const childName = S.fam.children[S.childId].name;
    if (!confirm(`Xoá máy "${deviceLabel(uid)}" của ${childName}?\n\nMáy sẽ không còn bị giới hạn hay theo dõi, toàn bộ dữ liệu của máy bị xoá. Muốn quản lý lại thì mở app trên máy đó và ghép nối lại.`)) return;
    const updates = {};
    for (const node of ["devices", "deviceRules", "deviceState", "usage", "labels", "alerts", "location", "screenshot", "commands", "requests", "pairing"]) {
      updates[`${node}/${uid}`] = null;
    }
    await attempt(famUpdate(updates), "Không xoá được máy — kiểm tra mạng.");
  },

  points: d => attempt(
    runTransaction(famRef(`children/${S.childId}/points`), cur => Math.max(0, (cur || 0) + Number(d.n))),
    "Không cập nhật được điểm — kiểm tra mạng."),
  renameChild: () => {
    const c = S.fam.children[S.childId];
    const name = prompt("Tên của bé:", c.name);
    if (name === null || !name.trim()) return;
    const grade = prompt("Lớp (để trống nếu không cần):", c.grade || "");
    return attempt(update(famRef(`children/${S.childId}`), { name: name.trim().slice(0, 40), grade: (grade || "").trim().slice(0, 10) }),
      "Không đổi được tên — kiểm tra mạng.");
  },
  deleteChild: async () => {
    const c = S.fam.children[S.childId];
    if (devicesOf(S.childId).length) {
      alert(`Hãy xoá hoặc chuyển hết máy của ${c.name} trước khi xoá bé.`);
      return;
    }
    if (!confirm(`Xoá ${c.name} cùng điểm thưởng, thời khoá biểu và tin nhắn?`)) return;
    const id = S.childId;
    await attempt(famUpdate({ [`children/${id}`]: null, [`schedule/${id}`]: null, [`messages/${id}`]: null }),
      "Không xoá được — kiểm tra mạng.");
  },
  deleteSchedule: d => attempt(set(famRef(`schedule/${S.childId}/${d.id}`), null), "Không xoá được — kiểm tra mạng."),
};

document.addEventListener("click", e => {
  const el = e.target.closest("[data-act]");
  if (!el) return;
  if (el.tagName === "A") e.preventDefault();
  if (el.id === "lightbox" && e.target !== el) return; // bấm vào ảnh trong khung phóng to
  const fn = actions[el.dataset.act];
  if (fn) fn(el.dataset, el);
});

$("dailyLimitForm").addEventListener("submit", e => {
  e.preventDefault();
  const v = parseInt($("dailyLimitInput").value, 10);
  if (isNaN(v) || v < 0) return;
  $("dailyLimitInput").blur();
  attempt(set(famRef(`deviceRules/${S.deviceUid}/dailyLimit`), v), "Không lưu được giới hạn — kiểm tra mạng.");
});
$("customBlockForm").addEventListener("submit", e => {
  e.preventDefault();
  const pkg = $("customPkgInput").value.trim();
  if (!/^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$/.test(pkg)) { showError("Tên gói không hợp lệ, ví dụ đúng: com.example.app"); return; }
  $("customPkgInput").value = "";
  attempt(set(famRef(`deviceRules/${S.deviceUid}/blocked/${pkgKey(pkg)}`), true), "Không lưu được — kiểm tra mạng.");
});
$("appLimits").addEventListener("change", e => {
  const input = e.target.closest(".app-limit");
  if (!input) return;
  const v = parseInt(input.value, 10);
  attempt(set(famRef(`deviceRules/${S.deviceUid}/appLimits/${pkgKey(input.dataset.pkg)}`), v > 0 ? v : null),
    "Không lưu được giới hạn — kiểm tra mạng.");
});
$("locationToggle").addEventListener("change", e => {
  attempt(set(famRef(`deviceRules/${S.deviceUid}/locationEnabled`), e.target.checked), "Không lưu được — kiểm tra mạng.");
});
$("scheduleForm").addEventListener("submit", e => {
  e.preventDefault();
  const item = { day: $("scheduleDay").value, time: $("scheduleTime").value, title: $("scheduleTitle").value.trim() };
  if (!item.time || !item.title) return;
  $("scheduleTitle").value = "";
  S.scheduleDay = item.day === "daily" ? S.scheduleDay : item.day;
  attempt(push(famRef(`schedule/${S.childId}`), item), "Không lưu được lịch — kiểm tra mạng.");
});
$("chatForm").addEventListener("submit", e => {
  e.preventDefault();
  const text = $("chatInput").value.trim();
  if (!text) return;
  $("chatInput").value = "";
  attempt(push(famRef(`messages/${S.childId}`), { from: "parent", text, at: serverTimestamp() }),
    "Không gửi được tin nhắn — kiểm tra mạng.");
});

// ---------- Duyệt máy, thưởng giờ ----------
function openApproveDialog(uid) {
  const p = S.fam.pairing[uid];
  if (!p) return;
  const sameName = childIds().find(id => (S.fam.children[id].name || "").trim().toLowerCase() === p.childName.trim().toLowerCase());
  $("approveBody").innerHTML = `
    <div class="card-title">Duyệt máy ${esc(p.model || "")}</div>
    <div>Bé nhập tên: <b>${esc(p.childName)}</b>. Máy này là của bé nào?</div>
    <div class="choice-list">
      ${childIds().map(id => `<button class="btn ${id === sameName ? "primary" : ""}" data-act="approve" data-uid="${esc(uid)}" data-child="${esc(id)}">
        Máy của ${esc(S.fam.children[id].name)}${id === sameName ? " (cùng tên)" : ""}</button>`).join("")}
      ${sameName ? "" : `<button class="btn primary" data-act="approve" data-uid="${esc(uid)}">Thêm bé mới: ${esc(p.childName)}</button>`}
      <button class="btn" data-act="closeDialog">Huỷ</button>
    </div>`;
  $("approveDialog").showModal();
}

function openMoveDialog() {
  const others = childIds().filter(id => id !== S.childId);
  if (!others.length) { alert("Chưa có bé nào khác để chuyển máy sang."); return; }
  $("approveBody").innerHTML = `
    <div class="card-title">Chuyển máy ${esc(deviceLabel(S.deviceUid))}</div>
    <div>Chuyển sang bé (giới hạn của máy giữ nguyên):</div>
    <div class="choice-list">
      ${others.map(id => `<button class="btn" data-act="moveTo" data-child="${esc(id)}">${esc(S.fam.children[id].name)}</button>`).join("")}
      <button class="btn" data-act="closeDialog">Huỷ</button>
    </div>`;
  $("approveDialog").showModal();
}

// Duyệt = gắn máy vào một bé (có sẵn hoặc tạo mới) và xoá yêu cầu, trong một lần
// ghi. Máy mới nhận giới hạn của máy khác cùng bé (nếu có), để ba mẹ khỏi đặt lại.
async function approveDevice(uid, childId) {
  const p = S.fam.pairing[uid];
  $("approveDialog").close();
  if (!p) return;
  const updates = {};
  if (!childId) {
    childId = push(famRef("children")).key;
    updates[`children/${childId}`] = { name: p.childName.trim(), grade: (p.grade || "").trim(), points: 0, createdAt: serverTimestamp() };
  }
  const sibling = devicesOf(childId)[0];
  const baseRules = sibling && S.fam.deviceRules[sibling] ? { ...S.fam.deviceRules[sibling] } : { dailyLimit: DEFAULT_DAILY_LIMIT };
  delete baseRules.bonus;
  updates[`devices/${uid}`] = { childId, name: (p.model || "Thiết bị").slice(0, 40), addedAt: serverTimestamp() };
  updates[`deviceRules/${uid}`] = baseRules;
  updates[`pairing/${uid}`] = null;
  const ok = await attempt(famUpdate(updates), "Không duyệt được máy — kiểm tra mạng.");
  if (ok !== null) { S.childId = childId; S.deviceUid = uid; scheduleRender(); }
}

async function grantBonus(uid, minutes, fromRequest) {
  if (!uid || !minutes) return;
  const today = todayKey();
  const updates = {
    [`deviceRules/${uid}/bonus/${today}`]: rulesOf(uid).bonusToday + minutes,
    [`commands/${uid}/bonus`]: { minutes, at: serverTimestamp() },
  };
  if (fromRequest || pendingRequests(uid).length) {
    for (const [id] of pendingRequests(uid)) updates[`requests/${uid}/${id}/status`] = "approved";
  }
  await attempt(famUpdate(updates), "Không thưởng được thời gian — kiểm tra mạng.");
}
