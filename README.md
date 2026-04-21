# Minecraft Restic Backup (Paper + Fabric)

Monorepo with shared backup core and two runtime adapters:

- `core`: backup orchestration, restic process execution, scheduling, retention, retries, hooks, webhooks
- `adapter-paper`: Paper plugin entrypoint and `/backup now|status`
- `adapter-fabric`: Fabric 26.1 мод с автоматическим планировщиком и консистентностью через серверные команды

## Current Implementation Status

Implemented in this iteration:

- Shared `BackupManager` pipeline:
  - `save-off`
  - `save-all flush`
  - `restic backup`
  - guaranteed `save-on` in `finally`
  - `restic forget --keep-last N --prune`
  - optional `restic prune`
- Restic execution via runtime resolver (PATH, explicit binary, or download URL) with timeout and stdout/stderr capture
- Fixed-delay and cron-like scheduler
- Single-flight guard (no parallel backups)
- Retry policy after failed backup
- Hook support (`pre`, `post`)
- Webhook notifications (`start`, `success`, `failure`)
- TOML config loader with support for `.secret` files
- Paper adapter commands: `/backup now`, `/backup status`
- Fabric adapter for `Minecraft 26.1.x` only
- Fabric consistency via in-process server commands (`save-off` / `save-all flush` / `save-on`)

## Config Format

Main config file is `backup.toml`.

```toml
repository = "s3:https://garage.internal:3900/minecraft-backups"
paths = ["./world", "./plugins"]
exclude = ["logs/**", "cache/**", "*.tmp", "session.lock"]
compression = "auto"
archive_prefix = "paper"
working_directory = "."
timeout = "2h"

[schedule]
interval = "3h"
run_on_startup = false
# cron = "0 */3 * * *"

[retention]
keep_last = 16
compact = false

[environment]
AWS_ACCESS_KEY_ID = "garage-access-key"
AWS_SECRET_ACCESS_KEY = "garage-secret-key"
AWS_REGION = "garage"
AWS_ENDPOINT_URL = "https://garage.internal:3900"
# RESTIC_EXECUTABLE = "./tools/restic/restic"
# RESTIC_DOWNLOAD_URL = "https://example.com/restic"
# RESTIC_DOWNLOAD_SHA256 = "<sha256>"

[environment_files]
RESTIC_PASSWORD = "secrets/restic_password.secret"

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

- Доступен S3-совместимый backend Garage
- Доступен `restic` (через PATH, `RESTIC_EXECUTABLE` или `RESTIC_DOWNLOAD_URL`)
- Для Paper: Java 21+
- Для Fabric 26.1.x: Java 25

На игровом сервере не требуется SSH-транспорт к backup-host: restic пишет напрямую в S3-совместимый backend.

### 2) Сборка

```powershell
./gradlew build
```

Артефакты после сборки:

- Paper: `adapter-paper/build/libs/adapter-paper-0.1.0-SNAPSHOT.jar`
- Fabric: `adapter-fabric/build/libs/adapter-fabric-0.1.0-SNAPSHOT.jar`

### 3) Настройка Restic + Garage (обязательно)

Ниже минимальный рабочий сценарий.

1. Создайте bucket в Garage, например `minecraft-backups`.
1. Сгенерируйте S3 ключи доступа для backup-клиента.
1. Инициализируйте restic-репозиторий с клиента, где есть `restic`:

```bash
export RESTIC_PASSWORD='YOUR_STRONG_PASSWORD'
export AWS_ACCESS_KEY_ID='garage-access-key'
export AWS_SECRET_ACCESS_KEY='garage-secret-key'
export AWS_REGION='garage'
export AWS_ENDPOINT_URL='https://garage.internal:3900'
restic -r s3:https://garage.internal:3900/minecraft-backups init
```

1. Проверьте доступ:

```bash
restic -r s3:https://garage.internal:3900/minecraft-backups snapshots
```

1. В конфиге `backup.toml` укажите:

- `repository = "s3:https://garage.internal:3900/minecraft-backups"`
- нужные `paths`
- `environment_files.RESTIC_PASSWORD` (через `.secret`, не в открытом виде)
- `environment.AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, `AWS_ENDPOINT_URL`

1. При необходимости настройте бинарник restic:

- `environment.RESTIC_DOWNLOAD_URL` - URL прямого бинарника restic для вашей OS/архитектуры
- `environment.RESTIC_DOWNLOAD_SHA256` - контрольная сумма SHA-256 для проверки скачанного файла
- (опционально) `environment.RESTIC_EXECUTABLE` - путь к уже подготовленному бинарнику restic

По умолчанию скачанный бинарник кешируется в `./.resticbackup/bin` относительно `working_directory`.

Пример секрета:

- файл: `secrets/restic_password.secret`
- содержимое: только пароль, без кавычек

### 4) Установка на Paper

1. Скопируйте JAR в `plugins/`.
2. Запустите сервер один раз (создастся `plugins/BorgBackup/backup.toml` и `plugins/BorgBackup/secrets/restic_password.secret`).
3. Отредактируйте `backup.toml` и `.secret`.
4. Перезапустите сервер.
5. Команды:

- `/backup now`
- `/backup status`

### 5) Установка на Fabric 26.1.x

1. Скопируйте JAR в `mods/`.
2. Убедитесь, что установлен совместимый `fabric-api` для `26.1.x`.
3. Запустите сервер один раз (создастся `config/borgbackup/backup.toml` и `config/borgbackup/secrets/restic_password.secret`).
4. Для консистентности мод использует серверные команды напрямую и не требует отдельной RCON-настройки.
5. Отредактируйте `backup.toml` и `.secret`.
6. Перезапустите сервер.

Важно для Fabric:

- Текущая версия таргетирует только `Minecraft 26.1.x`.
- Ручные команды доступны: `/backup now` и `/backup status`.

### 6) Развертывание в Pterodactyl (рекомендуемый сценарий)

Для restic+garage SSH-ключи не нужны. Основная задача - безопасно передать S3 креды и `RESTIC_PASSWORD`.

1. Добавьте в `backup.toml` блок `[environment]` c `AWS_*` и `AWS_ENDPOINT_URL`.
1. Храните пароль только в `secrets/restic_password.secret`.
1. Для Fabric удобно включить запуск бэкапа сразу при старте:

```toml
[schedule]
run_on_startup = true
```

1. Проверьте в логах, что preflight проходит:

- `[borgbackup/fabric] Restic preflight successful. restic ...`

Если preflight успешен, runtime restic доступен, и можно отлаживать только доступ до Garage endpoint.

## Notes About Security

- Password is never passed via restic CLI arguments.
- Use env (`RESTIC_PASSWORD`) loaded from `.secret` files.
- Do not store repository password or S3 secrets in git.

## Commands

- `/backup now`: triggers async backup run
- `/backup status`: prints last run time, duration, result, and stats

> Commands apply to both Paper and Fabric adapters.

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

- Add integration tests with fake restic binary and timeout/error scenarios
- Add explicit TPS impact checks on running servers
- Add optional manual trigger endpoint/command for Fabric 26.1 adapter
