# Minecraft Borg Backup (Paper + Fabric)

Monorepo with shared backup core and two runtime adapters:

- `core`: backup orchestration, borg process execution, scheduling, retention, retries, hooks, webhooks
- `adapter-paper`: Paper plugin entrypoint and `/backup now|status`
- `adapter-fabric`: Fabric 26.1 мод с автоматическим планировщиком и RCON-consistency

## Current Implementation Status

Implemented in this iteration:

- Shared `BackupManager` pipeline:
  - `save-off`
  - `save-all flush`
  - `borg create`
  - guaranteed `save-on` in `finally`
  - `borg prune --keep-last=N`
  - optional `borg compact`
- Borg execution via embedded runtime (auto-resolve/download) with timeout and stdout/stderr capture
- Fixed-delay and cron-like scheduler
- Single-flight guard (no parallel backups)
- Retry policy after failed backup
- Hook support (`pre`, `post`)
- Webhook notifications (`start`, `success`, `failure`)
- TOML config loader with support for `.secret` files
- Paper adapter commands: `/backup now`, `/backup status`
- Fabric adapter for `Minecraft 26.1.x` only
- Fabric consistency via RCON (`save-off` / `save-all flush` / `save-on`)

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
# BORG_EXECUTABLE = "./tools/borg/borg"
# BORG_DOWNLOAD_URL = "https://github.com/borgbackup/borg/releases/download/1.4.4/borg-linux-glibc231-x86_64"
# BORG_DOWNLOAD_SHA256 = "<sha256>"

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

## Установка

### 1) Требования

- На backup-бэкенде установлен `borg`
- Настроен SSH-доступ от игрового сервера к backup-бэкенду
- Для Paper: Java 21+
- Для Fabric 26.1.x: Java 25

На игровом сервере отдельная установка `borg` больше не требуется: мод/плагин использует встроенный runtime.

### 2) Сборка

```powershell
./gradlew build
```

Артефакты после сборки:

- Paper: `adapter-paper/build/libs/adapter-paper-0.1.0-SNAPSHOT.jar`
- Fabric: `adapter-fabric/build/libs/adapter-fabric-0.1.0-SNAPSHOT.jar`

### 3) Настройка Borg backend (обязательно)

Ниже минимальный рабочий сценарий.

1. Создайте директорию репозитория на backup-бэкенде, например `/backups/minecraft_repo`.
1. Настройте SSH-ключ на игровом сервере для пользователя backup-бэкенда.
1. Инициализируйте borg-репозиторий с клиента, где есть `borg` (это может быть backup-бэкенд):

```bash
export BORG_PASSPHRASE='YOUR_STRONG_PASSPHRASE'
borg init --encryption=repokey-blake2 user@backup:/backups/minecraft_repo
```

1. Проверьте доступ:

```bash
borg info user@backup:/backups/minecraft_repo
```

1. В конфиге `backup.toml` укажите:

- `repository = "user@backup:/backups/minecraft_repo"`
- нужные `paths`
- `environment_files.BORG_PASSPHRASE` (через `.secret`, не в открытом виде)

1. Настройте источник встроенного бинарника Borg (рекомендуется):

- `environment.BORG_DOWNLOAD_URL` - URL прямого бинарника Borg для вашей OS/архитектуры
- `environment.BORG_DOWNLOAD_SHA256` - контрольная сумма SHA-256 для проверки скачанного файла
- (опционально) `environment.BORG_EXECUTABLE` - путь к уже подготовленному бинарнику Borg

По умолчанию бинарник кешируется в `./.borgbackup/bin` относительно `working_directory`.
На Linux/macOS для популярных архитектур используется авто-URL по умолчанию; для Windows задайте `BORG_DOWNLOAD_URL` или `BORG_EXECUTABLE` явно.

Пример секрета:

- файл: `secrets/borg_passphrase.secret`
- содержимое: только passphrase, без кавычек

### 4) Установка на Paper

1. Скопируйте JAR в `plugins/`.
2. Запустите сервер один раз (создастся `plugins/BorgBackup/backup.toml` и `plugins/BorgBackup/secrets/borg_passphrase.secret`).
3. Отредактируйте `backup.toml` и `.secret`.
4. Перезапустите сервер.
5. Команды:

- `/backup now`
- `/backup status`

### 5) Установка на Fabric 26.1.x

1. Скопируйте JAR в `mods/`.
2. Убедитесь, что установлен совместимый `fabric-api` для `26.1.x`.
3. Запустите сервер один раз (создастся `config/borgbackup/backup.toml` и `config/borgbackup/secrets/borg_passphrase.secret`).
4. Мод автоматически включает/настраивает RCON в `server.properties` для консистентности.
5. Отредактируйте `backup.toml` и `.secret`.
6. Перезапустите сервер.

Важно для Fabric:

- Текущая версия таргетирует только `Minecraft 26.1.x`.
- Ручные команды `/backup now|status` в Fabric-адаптере не используются, бэкапы идут через scheduler из `backup.toml`.

## Notes About Security

- Passphrase is never passed via borg CLI arguments.
- Use env (`BORG_PASSPHRASE`) loaded from `.secret` files.
- Do not store passphrase in git or in plain config.

## Commands

- `/backup now`: triggers async backup run
- `/backup status`: prints last run time, duration, result, and stats

> Commands apply to Paper adapter. Fabric 26.1 adapter currently uses scheduled runs.

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
- Add optional manual trigger endpoint/command for Fabric 26.1 adapter
