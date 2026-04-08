# lcmf-request-state

`lcmf-request-state` — небольшая библиотека поверх `lcmf-http`, которая
помогает модулю вести типовое состояние одного запроса, не забирая у него
владение предметной логикой.

Ее роль узкая и практическая:

- `lcmf-http` отвечает за транспортный запрос и нормализацию ошибок;
- `lcmf-request-state` отвечает за типовое состояние выполнения одного
  request-flow;
- модуль по-прежнему отвечает за смысл операции, состав своего состояния и
  публикацию сообщений в `bus`.

## Главная модель

Библиотека рассчитана на простой и надежный подход:

- один `atom` — один осмысленный сценарий запроса;
- если у модуля есть загрузка списка и отправка формы, это обычно два разных
  `atom`;
- если модулю нужен latest-only сценарий, он живет внутри этого же одного
  request-flow, а не поверх общего многозадачного state.

Для малого и среднего проекта это полезнее, чем один универсальный state на
весь модуль.

## Что дает библиотека

- начальное состояние запроса через `init-state`;
- переход в `:pending` через `start!`;
- переход в `:success` через `succeed!`;
- переход в `:error` через `fail!`;
- переход в `:cancelled` через `cancel!`;
- фоновое обновление через `start-refresh!`;
- latest-only дисциплину через `begin-run!` и `complete-run!`.

## Чего библиотека не делает

- не является кэшем;
- не является общим query-слоем;
- не синхронизирует модули между собой;
- не знает про маршруты, страницы и жизненный цикл интерфейса;
- не заменяет `bus`, `registry` или app-level composition;
- не решает за модуль, когда делать перечитывание, повтор или повторную
  синхронизацию.

## Форма состояния

Каноническая форма состояния такая:

```clojure
{:status :idle
 :data nil
 :error nil
 :revalidating? false
 :started-at nil
 :finished-at nil
 :last-success-at nil
 :request-id nil
 :correlation-id nil
 :retry-after-sec nil
 :current-run-id nil
 :last-completed-run-id nil}
```

Допустимые значения `:status`:

- `:idle`
- `:pending`
- `:success`
- `:error`
- `:cancelled`

Фоновое обновление выражается не отдельным статусом, а флагом
`:revalidating?`.

## Минимальный пример

```clojure
(require '[lcmf.request-state :as request-state])

(def catalog-load-state
  (atom (request-state/init-state)))

(request-state/start! catalog-load-state)

(request-state/succeed! catalog-load-state
                        {:items [{:id "slot-1"}]}
                        {:request-id "rid-1"
                         :correlation-id "cid-1"
                         :finished-at 1710000010})
```

После этого в `catalog-load-state` будет:

```clojure
{:status :success
 :data {:items [{:id "slot-1"}]}
 :error nil
 :request-id "rid-1"
 :correlation-id "cid-1"
 ...}
```

## Пример двух request-flow внутри модуля

```clojure
(def catalog-load-state
  (atom (request-state/init-state)))

(def booking-create-state
  (atom (request-state/init-state)))
```

Первый `atom` отвечает за загрузку списка, второй — за создание брони.
Библиотека не предполагает, что оба сценария должны безопасно жить в одном
общем request-state.

## Пример latest-only

```clojure
(let [run-id (request-state/begin-run! catalog-load-state)]
  (request-state/complete-run! catalog-load-state
                               run-id
                               {:status :success
                                :data {:items [1 2 3]}
                                :response {:request-id "rid-2"}}))
```

Если позже завершится более старый запуск с другим `run-id`, библиотека не
даст ему перетереть более новое состояние.

Если после `begin-run!` модуль пойдет по обычному пути через `start!`,
`succeed!`, `fail!` или `cancel!`, старый `run-id` будет сброшен и тоже не
сможет вернуться "из прошлого".

## Семантика отмены

Для текущей версии зафиксировано такое поведение:

- `cancelled` — это отдельный вид завершения;
- отмена не считается обычной ошибкой;
- отмена не стирает предыдущие успешные данные;
- модуль сам решает, нужно ли показывать это состояние в интерфейсе.

## Для какого кода библиотека подходит лучше всего

`lcmf-request-state` уже хорошо подходит для модульного кода, где нужно:

- показать первую загрузку;
- сохранить успешные данные;
- показать ошибку;
- пережить отмену без потери прошлых данных;
- отбрасывать устаревшие результаты в latest-only сценарии;
- держать отдельные request-flow в отдельных `atom`.

Если проекту нужен общий кэш, сложные правила устаревания, общий слой
координации нескольких источников данных или автоматическая синхронизация с
роутером и websocket, это должен быть отдельный верхний слой, а не ядро этой
библиотеки.

## Примеры в репозитории

- интерфейс библиотеки: [docs/INTERFACE.md](/home/algebrain/src/my/lcmm/lcmf-request-state/docs/INTERFACE.md)
- базовый пример использования: [examples/basic_usage.cljs](/home/algebrain/src/my/lcmm/lcmf-request-state/examples/basic_usage.cljs)

## Статус

Текущая версия сосредоточена на минимальном practically useful API для
модулей `LCMF`.

Это означает:

- небольшой и предсказуемый helper-слой;
- отдельный `atom` на отдельный request-flow;
- без глобального store;
- без кэша;
- без попытки подменить модульную архитектуру `LCMF`.
