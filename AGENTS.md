# ccez-launcher

Owner rules: `~/AGENTS.md`, `~/SWE/AGENTS.md`. The spec and stages are in
`PLAN.md`; keep its Status section current and delete what ships.

- Android only: Kotlin + Jetpack Compose. Word logic comes from the
  ccez-keys core (Rust, UniFFI), never re-written in Kotlin.
- Verify on the emulator with screenshots; never the owner's phone unless
  asked. Tests stay silent.
- Commit small, push to `origin main`. Releases are local
  (`scripts/release.sh`), no GitHub Actions on this private repo.
