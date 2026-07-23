#!/usr/bin/env node

import { spawn } from "node:child_process";
import { access, mkdtemp, rm } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const artifact = path.resolve(process.argv[2] || "design/template-v2/index.html");
const chromeBin = process.env.CHROME_BIN || "google-chrome-stable";
await access(artifact);

const port = await freePort();
const profile = await mkdtemp(path.join(os.tmpdir(), "lezi-template-v2-smoke-"));
const chrome = spawn(
  chromeBin,
  [
    "--headless=new",
    "--no-sandbox",
    "--disable-gpu",
    "--disable-background-networking",
    "--no-first-run",
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    pathToFileURL(artifact).href,
  ],
  { stdio: "ignore" },
);

let socket;
try {
  const target = await waitForTarget(port);
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });

  let requestId = 0;
  const pending = new Map();
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(String(event.data));
    if (!message.id) return;
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    if (message.error) request.reject(new Error(message.error.message));
    else request.resolve(message.result);
  });

  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++requestId;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });

  const evaluate = async (expression) => {
    const response = await send("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
      userGesture: true,
    });
    if (response.exceptionDetails) {
      throw new Error(response.exceptionDetails.text || "Runtime evaluation failed");
    }
    return response.result.value;
  };

  await send("Runtime.enable");
  await waitUntil(async () => evaluate(
    "document.readyState === 'complete' && document.querySelectorAll('.summary-item').length === 5",
  ));

  const result = await evaluate(`(() => {
    const failures = [];
    const check = (condition, label) => { if (!condition) failures.push(label); };
    const click = (selector) => {
      const element = document.querySelector(selector);
      check(Boolean(element), "missing " + selector);
      element?.click();
      return element;
    };

    check(document.querySelectorAll(".nav-item").length === 5, "five navigation items");
    check(document.querySelectorAll(".summary-item").length === 5, "five today summaries");
    check(document.querySelectorAll("#record-type-list [data-record-type]").length === 30, "30 record types");

    click('[data-view-target="summary"]');
    check(!document.querySelector("#view-summary").hidden, "summary navigation");
    check(document.querySelectorAll("#week-chart .week-day").length === 7, "seven-day summary grid");

    click('[data-view-target="growth"]');
    check(!document.querySelector("#view-growth").hidden, "growth navigation");
    check(document.querySelectorAll("#growth-metric [data-metric]").length === 3, "three growth metrics");

    click('[data-view-target="menu"]');
    click("#theme-toggle");
    check(document.documentElement.dataset.theme === "dark", "dark theme toggle");
    const storedAfterTheme = JSON.parse(localStorage.getItem("leji-dense-template-v2"));
    check(storedAfterTheme.settings.dark === true, "theme persistence");

    click('[data-view-target="records"]');
    const before = Number(document.querySelector("#record-count").textContent.match(/\\d+/)?.[0] || 0);
    click('[data-quick="pee"]');
    const after = Number(document.querySelector("#record-count").textContent.match(/\\d+/)?.[0] || 0);
    check(after === before + 1, "one-tap pee record");

    click("#record-fab");
    check(document.querySelector("#record-sheet").open, "record sheet opens");
    click('[data-record-type="formula"]');
    const recordDialog = document.querySelector("#record-dialog");
    check(recordDialog.open, "formula dialog opens");
    for (const field of ["amountMl", "preparedMl", "durationMin", "date", "time", "note"]) {
      check(Boolean(recordDialog.querySelector('[name="' + field + '"]')), "formula field " + field);
    }
    click("#record-dialog [data-close]");

    click('[data-quick="nursing"]');
    check(document.querySelector("#timer-dialog").open, "nursing timer opens");
    click('[data-timer-side="left"]');
    check(!document.querySelector("#session-bar").hidden, "timer persists across views");
    click("#timer-pause");

    return {
      failures,
      navItems: document.querySelectorAll(".nav-item").length,
      summaries: document.querySelectorAll(".summary-item").length,
      recordTypes: document.querySelectorAll("#record-type-list [data-record-type]").length,
      statusModes: document.querySelectorAll("#status-grid input").length,
    };
  })()`);

  if (result.failures.length) {
    throw new Error(`runtime smoke failed: ${result.failures.join(", ")}`);
  }
  console.log(
    `template-v2 runtime smoke: OK (${result.navItems} nav, ${result.summaries} summaries, ` +
      `${result.recordTypes} record types, ${result.statusModes} states)`,
  );
} finally {
  socket?.close();
  if (chrome.exitCode === null && chrome.signalCode === null) {
    const exited = new Promise((resolve) => chrome.once("exit", resolve));
    chrome.kill("SIGTERM");
    await Promise.race([exited, delay(2_000)]);
    if (chrome.exitCode === null && chrome.signalCode === null) {
      chrome.kill("SIGKILL");
      await Promise.race([exited, delay(1_000)]);
    }
  }
  await removeProfile(profile);
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      server.close(() => resolve(address.port));
    });
  });
}

async function waitForTarget(port) {
  let lastError;
  for (let attempt = 0; attempt < 80; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/list`);
      const targets = await response.json();
      const page = targets.find((target) => target.type === "page");
      if (page) return page;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw lastError || new Error("Chrome DevTools target did not become ready");
}

async function waitUntil(predicate) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("template did not finish rendering");
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function removeProfile(profilePath) {
  let lastError;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      await rm(profilePath, { recursive: true, force: true, maxRetries: 2, retryDelay: 50 });
      return;
    } catch (error) {
      lastError = error;
      await delay(50);
    }
  }
  throw lastError;
}
