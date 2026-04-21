# Minecraft Borg Backup (Paper + Fabric)

Monorepo with shared backup core and two runtime adapters:

- `core`: backup orchestration, borg process execution, scheduling, retention, retries, hooks, webhooks
- `adapter-paper`: Paper plugin entrypoint and `/backup now|status`
- `adapter-fabric`: Fabric mod entrypoint and `/backup now|status`

## Current Implementation Status

Implemented in this iteration:

- Shared `BackupManager` pipeline:
  - `save-off`
  - `save-all flush`
  - `borg create`
  - guaranteed `save-on` in `finally`
  - `borg prune --keep-last=N`
  - optional `borg compact`
- Borg execution in external process with timeout and stdout/stderr capture
- Fixed-delay and cron-like scheduler
- Single-flight guard (no parallel backups)
- Retry policy after failed backup
- Hook support (`pre`, `post`)
- Webhook notifications (`start`, `success`, `failure`)
- TOML config loader with support for `.secret` files
- Paper adapter commands: `/backup now`, `/backup status`
- Fabric adapter commands: `/backup now`, `/backup status`

## Config Format

Main config file is `backup.toml`.

```toml
repository = "user@backup:/backups/minecraft_repo"
paths = ["./world", "./plugins"]
exclude = ["logs/**", "cache/**", "*.tmp", "session.lock"]
compression = "lz4"
archive_prefix = "paper"
working_directory = "."
timeout = "2h"

[schedule]
interval = "3h"
# cron = "0 */3 * * *"

[retention]
keep_last = 16
compact = false

[environment]
# BORG_RSH = "ssh -i ~/.ssh/id_ed25519"

[environment_files]
BORG_PASSPHRASE = "secrets/borg_passphrase.secret"

[retry]
enabled = true
max_attempts = 2
delay = "5m"

[hooks]
pre = []
post = []
timeout = "10m"

[webhook]
enabled = false
url = ""
token = ""
timeout = "10s"
```

## Notes About Security

- Passphrase is never passed via borg CLI arguments.
- Use env (`BORG_PASSPHRASE`) loaded from `.secret` files.

## Commands

- `/backup now`: triggers async backup run
- `/backup status`: prints last run time, duration, result, and stats

## Build

This repository uses Gradle multi-module layout.

If Gradle wrapper is not generated yet in your environment, create it once:

```powershell
gradle wrapper
```

Then build:

```powershell
./gradlew build
```

## Next Steps

- Add integration tests with fake borg binary and timeout/error scenarios
- Add explicit TPS impact checks on running servers
- Validate Java 25 runtime compatibility with selected Paper/Fabric versions
