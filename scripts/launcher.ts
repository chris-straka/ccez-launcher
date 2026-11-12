#!/usr/bin/env bun
// Runs ccez-launcher from the Mac, over adb (Control.kt, AgentCommands.kt):
//   bun scripts/launcher.ts status|schema|dump|screen
//   bun scripts/launcher.ts get [setting]      set <setting> <json>
//   bun scripts/launcher.ts log [lines]        update
//   bun scripts/launcher.ts word <lang> <word> [new|learning|known]
//   bun scripts/launcher.ts backup [file]      restore [file]
// The layout, each one call, checked on the phone before anything changes,
// logged ("agent" events) and on screen at once:
//   apps [--json] [query]     every launchable app: label, key, profile,
//                             hidden, pinned, folder, word first
//   pins [--json]             the pinned row in order (folders with items)
//   pin <app> [--at N]        unpin <app|N>        move <app|N> <to>
//   hide <app>   unhide <app>   hidden [--json]
//   folder <name> <app> <app>…   (makes it, or adds to it)
//   unfolder <name>   rename-folder <old> <new>
//   gate <app> on|off         ("Word first" before the app)
//   open <app>   home   drawer
//   shortcuts <app> [--json]  pin-shortcut <app> <shortcut id|label> [--at N]
// <app> is a label (any case, a unique prefix will do), a package or a key
// (pkg/activity@user, as `apps` prints it). A name that fits several lists
// them and changes nothing. N counts the row from 0. Only installed apps
// can be pinned.
// Which device: LAUNCHER_SERIAL, or the only one connected. A computer on
// the tailnet reaches the phone over wireless debugging: LAUNCHER_SERIAL=
// <phone tailnet address>:<port> (the port Wireless debugging shows; z is
// paired, the address lives outside this repo) runs `adb connect` first.
import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";

const URI = "content://uk.ccez.launcher.control";

function devices(): string[] {
  const out = spawnSync("adb", ["devices"], { encoding: "utf8" });
  return (out.stdout ?? "")
    .split("\n")
    .slice(1)
    .filter((l) => l.endsWith("\tdevice"))
    .map((l) => l.split("\t")[0]);
}

let chosen: string | undefined;

function serial(): string {
  if (chosen) return chosen;
  const env = process.env.LAUNCHER_SERIAL;
  if (env) {
    // host:port (wireless debugging, over the tailnet): connect once.
    if (/^[^:\s]+:\d+$/.test(env) && !devices().includes(env)) {
      const c = spawnSync("adb", ["connect", env], { encoding: "utf8" });
      const said = ((c.stdout ?? "") + (c.stderr ?? "")).trim();
      if (!said.includes("connected to")) {
        throw new Error(`adb connect ${env}: ${said || "no answer"} (wireless debugging on, phone on the tailnet, right port?)`);
      }
    }
    return (chosen = env);
  }
  const found = devices();
  if (found.length === 1) return (chosen = found[0]);
  if (found.length === 0) throw new Error("no device: plug it in or start an emulator");
  throw new Error(`several devices (${found.join(", ")}); set LAUNCHER_SERIAL`);
}

const b64 = (s: string) => Buffer.from(s, "utf8").toString("base64");
const unb64 = (s: string) => Buffer.from(s, "base64").toString("utf8");
const quote = (s: string) => `'${s.replaceAll("'", "'\\''")}'`;

function call(method: string, arg?: string, value?: string): string {
  const s = serial();
  let cmd = `content call --uri ${URI} --method ${method}`;
  if (arg !== undefined) cmd += ` --arg ${quote(arg)}`;
  if (value !== undefined) cmd += ` --extra value:s:${quote(b64(value))}`;
  if (process.env.LAUNCHER_DEBUG) console.error(`adb -s ${s} shell ${cmd}`);
  const out = spawnSync("adb", ["-s", s, "shell", cmd], { encoding: "utf8" });
  const text = (out.stdout ?? "") + (out.stderr ?? "");
  if (text.includes("Could not find provider") || text.includes("Unknown authority")) {
    throw new Error("ccez-launcher isn't installed on that device");
  }
  const pick = (name: string): string | undefined => {
    // Bundle[{out=...}] or Bundle[{..., error=...}]: base64 can end in
    // "error=", so the name must open the entry.
    const m = new RegExp(`[{ ]${name}=`).exec(text);
    if (!m) return undefined;
    const rest = text.slice(m.index + m[0].length);
    const end = rest.search(/[,}]/);
    return unb64(rest.slice(0, end < 0 ? rest.length : end).trim());
  };
  const err = pick("error");
  if (err !== undefined) throw new Error(err);
  const got = pick("out");
  if (got === undefined) throw new Error(`unexpected answer from the device: ${text.trim()}`);
  return got;
}

function backup(file?: string): void {
  const s = serial();
  const out = spawnSync("adb", ["-s", s, "exec-out", "content", "read", "--uri", `${URI}/backup`]);
  if (out.status !== 0 || !out.stdout?.length) throw new Error("backup failed");
  if (file) {
    writeFileSync(file, out.stdout);
    console.log(`wrote ${file}`);
  } else {
    process.stdout.write(out.stdout);
  }
}

function restore(file?: string): void {
  const s = serial();
  const data = file ? readFileSync(file) : readFileSync(0);
  JSON.parse(data.toString("utf8")); // not JSON at all: fail here, not on the phone
  const out = spawnSync("adb", ["-s", s, "shell", `content write --uri ${URI}/restore`], { input: data });
  if (out.status !== 0) throw new Error(`restore failed: ${out.stderr?.toString() ?? ""}`);
  console.log("sent; `launcher log` says how it went");
}

// --- the layout (AgentCommands.kt) ---

type Json = any;

/** One layout call: its arguments go as one JSON object. */
function agent(method: string, args: Record<string, unknown> = {}): string {
  return call(method, undefined, JSON.stringify(args));
}

/** Pulls `--json` and `--at N` out of the words; the rest stay in order. */
function flags(words: string[]): { rest: string[]; json: boolean; at?: number } {
  const rest: string[] = [];
  let json = false;
  let at: number | undefined;
  for (let i = 0; i < words.length; i++) {
    const w = words[i];
    if (w === "--json") json = true;
    else if (w === "--at") {
      const n = Number(words[++i]);
      if (!Number.isInteger(n)) throw new Error("--at needs a row position (0 first)");
      at = n;
    } else rest.push(w);
  }
  return { rest, json, at };
}

function need(words: string[], n: number, usage: string): void {
  if (words.length < n) throw new Error(`usage: launcher ${usage}`);
}

function appLine(a: Json): string {
  const notes: string[] = [];
  if (a.profile !== "main") notes.push(a.profile);
  if (a.hidden) notes.push("hidden");
  if (a.pinned !== null) notes.push(`pinned ${a.pinned}`);
  if (a.folder !== null) notes.push(`in ${a.folder}`);
  if (a.gated) notes.push("word first");
  return `${a.label}  ${a.key}${notes.length ? "  (" + notes.join(", ") + ")" : ""}`;
}

function pinLines(pins: Json[]): string[] {
  const out: string[] = [];
  for (const p of pins) {
    const head = `${String(p.index).padStart(2)}  `;
    if (p.type === "app") out.push(`${head}${p.label}  ${p.key}${p.installed ? "" : "  (not installed)"}`);
    else if (p.type === "shortcut") out.push(`${head}${p.label}  shortcut of ${p.app}  ${p.pkg}/${p.id}`);
    else {
      out.push(`${head}${p.label}  folder ${p.id}`);
      for (const i of p.items) out.push(`      ${i.label}  ${i.key ?? `${i.pkg}/${i.id}`}`);
    }
  }
  return out.length ? out : ["(nothing pinned)"];
}

function show(text: string, json: boolean, lines: (v: Json) => string[]): void {
  console.log(json ? text : lines(JSON.parse(text)).join("\n"));
}

const HELP = `launcher status|schema|dump|screen|update|sync [lang]
launcher get [setting]
launcher set <setting> <json>
launcher log [lines]
launcher word <lang> <word> [new|learning|known]
launcher backup [file]
launcher restore [file]
launcher apps [--json] [query]
launcher pins [--json]
launcher pin <app> [--at N]
launcher unpin <app|N>
launcher move <app|N> <to>
launcher hide <app>
launcher unhide <app>
launcher hidden [--json]
launcher folder <name> <app> <app>...
launcher unfolder <name>
launcher rename-folder <old> <new>
launcher gate <app> on|off
launcher open <app>
launcher home | drawer
launcher shortcuts <app> [--json]
launcher pin-shortcut <app> <shortcut id|label> [--at N]
<app>: label (any case, unique prefix), package or key. N: row position, 0 first.
Device: LAUNCHER_SERIAL (a serial, or host:port for wireless debugging), or the only one connected.`;

function main(): void {
  const [cmd, ...rest] = process.argv.slice(2);
  switch (cmd) {
    case "status": console.log(call("status")); break;
    case "schema": console.log(call("schema")); break;
    case "dump": console.log(call("dump")); break;
    case "screen": console.log(call("screen")); break;
    case "update": console.log(call("update")); break;
    case "sync": console.log(call("sync", rest[0])); break;
    case "get": console.log(call("get", rest[0])); break;
    case "set": {
      if (rest.length < 2) throw new Error("set needs a setting and a value");
      console.log(call("set", rest[0], rest.slice(1).join(" ")));
      break;
    }
    case "log": console.log(call("log", rest[0])); break;
    case "word": {
      if (rest.length < 2) throw new Error("word needs: lang word [new|learning|known]");
      console.log(call("word", rest.join(" ")));
      break;
    }
    case "backup": backup(rest[0]); break;
    case "restore": restore(rest[0]); break;
    default: layout(cmd, rest);
  }
}

function layout(cmd: string | undefined, words: string[]): void {
  const { rest, json, at } = flags(words);
  const joined = rest.join(" ");
  switch (cmd) {
    case "apps": {
      show(agent("apps", { q: joined }), json, (v) => (v.length ? v.map(appLine) : ["(no apps match)"]));
      break;
    }
    case "pins": show(agent("pins"), json, pinLines); break;
    case "hidden": {
      show(agent("hidden"), json, (v) =>
        v.length ? v.map((h: Json) => `${h.label ?? "(not installed)"}  ${h.key}`) : ["(nothing hidden)"]);
      break;
    }
    case "pin": need(rest, 1, "pin <app> [--at N]"); console.log(agent("pin", { app: joined, at })); break;
    case "unpin": need(rest, 1, "unpin <app|N>"); console.log(agent("unpin", { pin: joined })); break;
    case "move": {
      need(rest, 2, "move <app|N> <to>");
      const to = Number(rest[rest.length - 1]);
      if (!Number.isInteger(to)) throw new Error("move: <to> is a row position (0 first)");
      console.log(agent("move", { pin: rest.slice(0, -1).join(" "), to }));
      break;
    }
    case "hide": need(rest, 1, "hide <app>"); console.log(agent("hide", { app: joined })); break;
    case "unhide": need(rest, 1, "unhide <app>"); console.log(agent("unhide", { app: joined })); break;
    case "folder": {
      need(rest, 2, "folder <name> <app> <app>...");
      console.log(agent("folder", { name: rest[0], apps: rest.slice(1) }));
      break;
    }
    case "unfolder": need(rest, 1, "unfolder <name>"); console.log(agent("unfolder", { name: joined })); break;
    case "rename-folder": {
      if (rest.length !== 2) throw new Error("usage: launcher rename-folder <old> <new> (quote names with spaces)");
      console.log(agent("rename-folder", { old: rest[0], new: rest[1] }));
      break;
    }
    case "gate": {
      need(rest, 2, "gate <app> on|off");
      console.log(agent("gate", { app: rest.slice(0, -1).join(" "), on: rest[rest.length - 1] }));
      break;
    }
    case "open": need(rest, 1, "open <app>"); console.log(agent("open", { app: joined })); break;
    case "home": console.log(agent("home")); break;
    case "drawer": console.log(agent("drawer")); break;
    case "shortcuts": {
      need(rest, 1, "shortcuts <app>");
      show(agent("shortcuts", { app: joined }), json, (v) =>
        v.length
          ? v.map((s: Json) => `${s.label}  ${s.id}  ${s.kind}${s.enabled ? "" : " (off)"}`)
          : ["(no shortcuts)"]);
      break;
    }
    case "pin-shortcut": {
      if (rest.length !== 2) throw new Error("usage: launcher pin-shortcut <app> <shortcut id|label> [--at N] (quote names with spaces)");
      console.log(agent("pin-shortcut", { app: rest[0], shortcut: rest[1], at }));
      break;
    }
    case undefined:
    case "help":
    case "-h":
    case "--help":
      console.log(HELP);
      break;
    default:
      console.error(`no command called ${cmd}\n${HELP}`);
      process.exit(2);
  }
}

try {
  main();
} catch (e) {
  console.error(e instanceof Error ? e.message : String(e));
  process.exit(1);
}
