#!/usr/bin/env bun
// Runs ccez-launcher from the Mac, over adb (Control.kt):
//   bun scripts/launcher.ts status|schema|dump|screen
//   bun scripts/launcher.ts get [setting]      set <setting> <json>
//   bun scripts/launcher.ts log [lines]        update
//   bun scripts/launcher.ts word <lang> <word> [new|learning|known]
//   bun scripts/launcher.ts backup [file]      restore [file]
// Which device: LAUNCHER_SERIAL, or the only one connected.
import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";

const URI = "content://uk.ccez.launcher.control";

function serial(): string {
  const env = process.env.LAUNCHER_SERIAL;
  if (env) return env;
  const out = spawnSync("adb", ["devices"], { encoding: "utf8" });
  const devices = (out.stdout ?? "")
    .split("\n")
    .slice(1)
    .filter((l) => l.endsWith("\tdevice"))
    .map((l) => l.split("\t")[0]);
  if (devices.length === 1) return devices[0];
  if (devices.length === 0) throw new Error("no device: plug it in or start an emulator");
  throw new Error(`several devices (${devices.join(", ")}); set LAUNCHER_SERIAL`);
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
    const at = text.indexOf(`${name}=`);
    if (at < 0) return undefined;
    const rest = text.slice(at + name.length + 1);
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

const HELP = `launcher status|schema|dump|screen|update|sync [lang]
launcher get [setting]
launcher set <setting> <json>
launcher log [lines]
launcher word <lang> <word> [new|learning|known]
launcher backup [file]
launcher restore [file]
Device: LAUNCHER_SERIAL, or the only one connected.`;

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
