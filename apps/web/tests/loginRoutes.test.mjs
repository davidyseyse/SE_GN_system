import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const routerSource = readFileSync(new URL("../src/router.tsx", import.meta.url), "utf8");
const guardSource = readFileSync(new URL("../src/auth/RequireRole.tsx", import.meta.url), "utf8");
const teacherSource = readFileSync(new URL("../src/auth/TeacherLoginPage.tsx", import.meta.url), "utf8");
const studentSource = readFileSync(new URL("../src/auth/StudentLoginPage.tsx", import.meta.url), "utf8");
const appShellSource = readFileSync(new URL("../src/ui/AppShell.tsx", import.meta.url), "utf8");

test("teacher and student login use separate routes and forms", () => {
  assert.match(routerSource, /path: "teacher\/login", element: <TeacherLoginPage/);
  assert.match(routerSource, /path: "student\/login", element: <StudentLoginPage/);
  assert.match(teacherSource, /<h2>教師登入<\/h2>/);
  assert.match(teacherSource, /adminLogin/);
  assert.doesNotMatch(teacherSource, /studentLogin/);
  assert.match(studentSource, /<h2>學生登入<\/h2>/);
  assert.match(studentSource, /studentLogin/);
  assert.doesNotMatch(studentSource, /adminLogin/);
  assert.doesNotMatch(teacherSource, /<select/);
  assert.doesNotMatch(teacherSource, /前往學生登入|to="\/student\/login"/);
  assert.doesNotMatch(studentSource, /前往教師登入|to="\/teacher\/login"/);
});

test("student selects an existing classroom before joining", () => {
  assert.match(studentSource, /listAvailableClassrooms/);
  assert.match(studentSource, /aria-label="選擇課堂"/);
  assert.match(studentSource, /<option value="">請選擇課堂<\/option>/);
  assert.match(studentSource, /classroom\.name.*classroom\.code/s);
  assert.doesNotMatch(studentSource, /placeholder="課堂代碼"/);
  assert.match(studentSource, /disabled=\{!canJoin\}/);
});

test("application header shows only the current audience login action", () => {
  assert.match(appShellSource, /location\.pathname\.startsWith\("\/teacher"\)/);
  assert.match(appShellSource, /isTeacherArea \? \(/);
  assert.match(appShellSource, /to="\/teacher\/login"/);
  assert.match(appShellSource, /to="\/student\/login"/);
});

test("role guards redirect to the matching login page", () => {
  assert.match(guardSource, /role === "TEACHER" \? "\/teacher\/login" : "\/student\/login"/);
});
