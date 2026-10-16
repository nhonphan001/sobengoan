// Kiểm tra luật bảo mật Realtime Database trên Firebase emulator.
// Chạy: npm run test:rules
import { readFileSync } from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { get, ref, remove, set, update } from "firebase/database";

const FID = "ABC123";
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-sobengoan",
    database: { rules: readFileSync("database.rules.json", "utf8"), host: "127.0.0.1", port: 9000 },
  });
});
after(() => env.cleanup());

const parent = () => env.authenticatedContext("parent").database();
const stranger = () => env.authenticatedContext("stranger").database();
const tablet = () => env.authenticatedContext("tablet").database(); // máy đã duyệt, của bé "an"
const phone = () => env.authenticatedContext("phone").database();   // máy đang xin ghép nối
const p = path => `families/${FID}/${path}`;

beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(ctx => set(ref(ctx.database(), `families/${FID}`), {
    owner: "parent",
    children: { an: { name: "An", points: 5 }, binh: { name: "Bình", points: 0 } },
    devices: { tablet: { childId: "an", name: "Máy tính bảng" } },
    deviceRules: { tablet: { dailyLimit: 60 } },
    schedule: { an: { s1: { day: "daily", time: "19:00", title: "Học bài" } }, binh: { s1: { day: "T2", time: "7:00", title: "Đi học" } } },
    messages: { an: { m1: { from: "parent", text: "Chào con", at: 1 } } },
    pairing: { phone: { childName: "Bình", requestedAt: 1 } },
  }));
});

test("phụ huynh tạo gia đình mới, người khác không chiếm được mã đã có", async () => {
  await assertSucceeds(set(ref(parent(), "families/NEW111"), { owner: "parent", createdAt: 1 }));
  await assertFails(set(ref(stranger(), `families/${FID}`), { owner: "stranger" }));
  await assertFails(set(ref(stranger(), "families/NEW222"), { owner: "parent" }));
});

test("chỉ chủ hộ đọc được dữ liệu gia đình", async () => {
  await assertSucceeds(get(ref(parent(), `families/${FID}`)));
  await assertFails(get(ref(stranger(), `families/${FID}`)));
  await assertFails(get(ref(tablet(), `families/${FID}`)));
});

test("máy mới gửi yêu cầu ghép nối, chủ hộ duyệt hoặc từ chối được", async () => {
  await assertSucceeds(set(ref(env.authenticatedContext("newdev").database(), p("pairing/newdev")),
    { childName: "Chi", requestedAt: 2 }));
  // Không ghi hộ máy khác, không gửi tên rỗng
  await assertFails(set(ref(stranger(), p("pairing/newdev")), { childName: "X", requestedAt: 2 }));
  await assertFails(set(ref(stranger(), p("pairing/stranger")), { childName: "", requestedAt: 2 }));
  // Gia đình không tồn tại
  await assertFails(set(ref(stranger(), "families/NOPE00/pairing/stranger"), { childName: "X", requestedAt: 2 }));
  // Chủ hộ xoá được yêu cầu (Từ chối) — bản cũ bị chặn chỗ này
  await assertSucceeds(remove(ref(parent(), p("pairing/newdev"))));
  // Duyệt: gắn máy vào bé và xoá yêu cầu cùng lúc
  await assertSucceeds(update(ref(parent(), `families/${FID}`), {
    "devices/phone": { childId: "binh", name: "Điện thoại" }, "pairing/phone": null,
  }));
});

test("máy đã duyệt đọc được giới hạn của mình, không đọc được của máy khác", async () => {
  await assertSucceeds(get(ref(tablet(), p("devices/tablet"))));
  await assertSucceeds(get(ref(tablet(), p("deviceRules/tablet"))));
  await assertSucceeds(get(ref(tablet(), p("commands/tablet"))));
  await assertFails(set(ref(tablet(), p("deviceRules/tablet/dailyLimit")), 999));
  await assertFails(get(ref(tablet(), p("deviceRules/phone"))));
  // Máy chưa duyệt chỉ đọc được node devices của chính nó (để biết đã được duyệt chưa)
  await assertSucceeds(get(ref(phone(), p("devices/phone"))));
  await assertFails(get(ref(phone(), p("deviceRules/phone"))));
});

test("máy đã duyệt ghi trạng thái, giờ dùng, xin thêm giờ của chính nó", async () => {
  const db = tablet();
  await assertSucceeds(set(ref(db, p("deviceState/tablet")), { lastSeen: 1 }));
  await assertSucceeds(set(ref(db, p("usage/tablet/2026-10-16")), { "com,google,android,youtube": 12 }));
  await assertSucceeds(set(ref(db, p("alerts/tablet/a1")), { type: "BLOCKED_APP", at: 1 }));
  await assertSucceeds(set(ref(db, p("requests/tablet/r1")), { type: "extension", status: "pending", at: 1 }));
  await assertFails(set(ref(db, p("requests/tablet/r1")), { type: "extension", status: "approved", at: 1 }));
  await assertFails(set(ref(db, p("deviceState/phone")), { lastSeen: 1 }));
  // Máy chưa duyệt không ghi được gì
  await assertFails(set(ref(phone(), p("deviceState/phone")), { lastSeen: 1 }));
  // Chủ hộ cập nhật trạng thái yêu cầu
  await assertSucceeds(set(ref(parent(), p("requests/tablet/r1/status")), "approved"));
});

test("máy của bé chỉ thấy phần chung của đúng bé đó", async () => {
  await assertSucceeds(get(ref(tablet(), p("children/an"))));
  await assertSucceeds(get(ref(tablet(), p("schedule/an"))));
  await assertSucceeds(get(ref(tablet(), p("messages/an"))));
  await assertFails(get(ref(tablet(), p("children/binh"))));
  await assertFails(get(ref(tablet(), p("schedule/binh"))));
  await assertFails(set(ref(tablet(), p("children/an/points")), 999));
});

test("máy của bé gửi tin nhắn mới, không sửa hay giả tin của phụ huynh", async () => {
  const db = tablet();
  await assertSucceeds(set(ref(db, p("messages/an/m2")), { from: "child", text: "Dạ", at: 2 }));
  await assertFails(set(ref(db, p("messages/an/m1")), { from: "child", text: "sửa", at: 2 }));
  await assertFails(set(ref(db, p("messages/an/m3")), { from: "parent", text: "giả", at: 2 }));
  await assertFails(set(ref(db, p("messages/binh/m3")), { from: "child", text: "Dạ", at: 2 }));
});

test("xoá thiết bị: chủ hộ xoá hết dữ liệu của máy, máy mất quyền ngay", async () => {
  await assertSucceeds(update(ref(parent(), `families/${FID}`), {
    "devices/tablet": null, "deviceRules/tablet": null, "deviceState/tablet": null,
    "usage/tablet": null, "commands/tablet": null,
  }));
  await assertFails(get(ref(tablet(), p("deviceRules/tablet"))));
  await assertFails(set(ref(tablet(), p("deviceState/tablet")), { lastSeen: 2 }));
  await assertFails(get(ref(tablet(), p("schedule/an"))));
  // Máy bị xoá được phép xin ghép nối lại
  await assertSucceeds(set(ref(tablet(), p("pairing/tablet")), { childName: "An", requestedAt: 3 }));
});
