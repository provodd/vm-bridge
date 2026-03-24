# VMBridge

Плагин-мост между **VillagerMarket** и **SimpleClans**. Назначает магазины торговцев на ветки рынка (green, blue, pink, orange) и автоматически зачисляет налог с каждой покупки в казну клана-владельца ветки.

---

## Зависимости

| Плагин | Обязательный | Версия |
|---|---|---|
| [VillagerMarket](https://www.spigotmc.org/resources/82965/) | Да | 1.13.2+ |
| [SimpleClans](https://www.spigotmc.org/resources/71242/) | Да | 2.19.2+ |
| [PlaceholderAPI](https://www.spigotmc.org/resources/6245/) | Да | 2.11.7+ |
| [Vault](https://www.spigotmc.org/resources/34315/) + экономика | Нет | любая |

---

## Установка

1. Скопируй `vm-bridge-1.0.0.jar` в папку `plugins/` сервера.
2. Убедись, что VillagerMarket, SimpleClans и PlaceholderAPI уже установлены.
3. Запусти/перезапусти сервер — создастся `plugins/VMBridge/config.yml`.
4. Настрой способ определения клана-владельца для каждой ветки (см. «Конфигурация»).

> При обновлении плагина новые параметры конфига добавляются автоматически — существующие настройки не затрагиваются.

---

## Принцип работы

```
Покупка у торговца
        │
        ▼
Назначен ветке? ──Нет──► (ничего не происходит)
        │
       Да
        │
        ▼
Читаем клан-владелец из кэша (обновляется по расписанию)
        │
        ▼
Считаем налог: сумма × ставка ветки
        │
        ▼
Зачисляем в казну клана (SimpleClans)
```

Кэш клана-владельца обновляется **раз в сутки** (или в заданный день недели) — либо через HTTP-запрос к внешнему URL, либо через PlaceholderAPI. На каждую транзакцию — только мгновенное чтение из памяти.

---

## Конфигурация

`plugins/VMBridge/config.yml`

```yaml
# Ставка налога для каждой ветки (0.05 = 5%)
tax-rate:
  green:  0.05
  blue:   0.05
  pink:   0.05
  orange: 0.05

# Способ определения клана-владельца ветки.
# Приоритет: если задан branch-owner-url — используется он,
#            иначе branch-owner-placeholder.

# URL, возвращающий клан-победитель. Запрашивается по расписанию (не на каждую транзакцию).
# Поддерживаемые форматы ответа:
#   plain text:  warriors
#   JSON:        {"clan": "warriors"}
branch-owner-url:
  green:  ''
  blue:   'http://your-site.com/api/branch/blue/winner'
  pink:   ''
  orange: ''

# PlaceholderAPI плейсхолдеры, возвращающие тег клана-владельца.
# Используются если branch-owner-url для ветки не задан.
branch-owner-placeholder:
  green:  '%simplesiege_market_green_owner%'
  blue:   ''
  pink:   '%simplesiege_market_pink_owner%'
  orange: '%simplesiege_market_orange_owner%'

# Расписание обновления кэша кланов — задаётся отдельно для каждой ветки.
# schedule: "daily"     — каждый день
#           "monday"    — каждый понедельник
#           "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
# time:     "HH:mm"    — время по серверному времени
# При старте плагина: daily-ветки обновляются сразу,
#                     weekday-ветки — только если сегодня нужный день.
branch-cache-schedule:
  green:
    schedule: "daily"
    time: "09:00"
  blue:
    schedule: "monday"
    time: "21:00"
  pink:
    schedule: "daily"
    time: "09:00"
  orange:
    schedule: "daily"
    time: "09:00"

# На каких событиях собирать налог:
# buy  — когда игрок ПОКУПАЕТ у магазина
# sell — когда игрок ПРОДАЁТ магазину
tax-on-buy:  true
tax-on-sell: false

# Выводить каждое списание налога в консоль (только для отладки)
debug: false

# Подключение к MySQL для логирования транзакций
mysql:
  host: localhost
  port: 3306
  database: minecraft
  username: root
  password: ''
```

---

## Плейсхолдеры

VMBridge регистрирует собственные PlaceholderAPI плейсхолдеры. Можно использовать в любом плагине, поддерживающем PAPI.

| Плейсхолдер | Описание | Пример |
|---|---|---|
| `%vmbridge_green_clan%` | Тег клана-владельца зелёной ветки | `warriors` |
| `%vmbridge_green_clan_colored%` | Тег с цветом клана из SimpleClans | `§6warriors` |
| `%vmbridge_blue_clan%` | Тег клана-владельца синей ветки | `dragons` |
| `%vmbridge_blue_clan_colored%` | Тег с цветом клана | `§bдраконы` |
| `%vmbridge_pink_clan%` | Тег клана-владельца розовой ветки | — |
| `%vmbridge_pink_clan_colored%` | Тег с цветом клана | — |
| `%vmbridge_orange_clan%` | Тег клана-владельца оранжевой ветки | — |
| `%vmbridge_orange_clan_colored%` | Тег с цветом клана | — |

> Значения берутся из кэша. Пока кэш не заполнен (сразу после установки или при ошибке запроса) — возвращается пустая строка.

---

## Команды

Все команды требуют разрешение `vmbridge.admin` (по умолчанию — оп).

| Команда | Описание |
|---|---|
| `/vmbridge setbranch <ветка>` | Назначить магазин (на который смотришь) ветке рынка |
| `/vmbridge removebranch` | Убрать назначение с магазина |
| `/vmbridge info` | Показать ветку, ставку, источник клана и кэшированное значение |
| `/vmbridge setrate <ветка> <ставка>` | Изменить ставку налога (например `0.07` = 7%) |
| `/vmbridge list` | Список всех назначенных магазинов |
| `/vmbridge reload` | Перезагрузить конфиг и данные |
| `/vmbridge refreshcache` | Принудительно обновить кэш кланов для всех веток |

### Пример использования

```
# Подойди к торговцу, посмотри на него, затем:
/vmbridge setbranch green

# Проверить результат (покажет ветку, URL/плейсхолдер и текущий клан из кэша):
/vmbridge info

# Изменить ставку для зелёной ветки на 10%:
/vmbridge setrate green 0.10

# Принудительно обновить кэш (например после смены владельца ветки):
/vmbridge refreshcache
```

---

## Права

| Право | Описание | По умолчанию |
|---|---|---|
| `vmbridge.admin` | Полный доступ ко всем командам | op |

---

## Данные

Назначения магазин → ветка хранятся в MySQL (таблица `vm_shops`). При недоступности MySQL используется резервный файл `plugins/VMBridge/data.yml`, который всегда поддерживается в актуальном состоянии.

---

## Сборка из исходников

Требования: Java 17+, Maven 3.6+

```bash
git clone <репозиторий>
cd vm-bridge
mvn clean package
# JAR: target/vm-bridge-1.0.0.jar
```
