# Интерфейс `lcmf-request-state`

Этот документ описывает публичный интерфейс `lcmf-request-state`.

Библиотека предназначена для типового состояния одного запроса поверх
`lcmf-http`. Она уменьшает повторяющийся код в модуле, но не забирает у
модуля владение его состоянием и решениями.

## Главная идея

`lcmf-request-state` не является:

- кэшем;
- общим query-слоем;
- глобальным store;
- менеджером жизненного цикла страницы;
- слоем межмодульной координации.

Это небольшой helper-слой для одного request-flow.

Практическая модель такая:

- один `atom` обслуживает один сценарий запроса;
- разные сценарии модуля обычно живут в разных `atom`;
- latest-only нужен для этого же одного request-flow, а не для многозадачного
  общего состояния.

## Публичный API

```clojure
(init-state)
(init-state opts)

(start! state)
(start! state opts)

(start-refresh! state)
(start-refresh! state opts)

(succeed! state data response)
(fail! state error)
(cancel! state)

(begin-run! state)
(complete-run! state run-id result)
```

Во всех функциях `state` — это `atom`.

## Каноническая форма состояния

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

Важно:

- статуса `:revalidating` в библиотеке нет;
- фоновое обновление выражается через `:revalidating? true`.

## `init-state`

Создает начальное состояние запроса.

```clojure
(request-state/init-state)
```

Можно передать начальные поля, но только из канонической формы состояния:

```clojure
(request-state/init-state {:status :success
                           :data {:items []}})
```

Если передать неизвестный ключ или недопустимый статус, библиотека бросит
`ExceptionInfo` с `:reason :invalid-argument`.

## `start!`

Переводит request-flow в обычный `:pending`.

```clojure
(request-state/start! state)
```

Или с дополнительными полями:

```clojure
(request-state/start! state
                      {:started-at 1710000000})
```

### Поведение

- `:status` становится `:pending`;
- `:error` очищается;
- `:revalidating?` становится `false`;
- прежние данные не стираются автоматически;
- активный latest-only запуск, если он был, сбрасывается.

Допустимые ключи `opts`:

- `:started-at`
- `:data`

## `start-refresh!`

Запускает фоновое обновление без потери предыдущих данных.

```clojure
(request-state/start-refresh! state)
```

### Поведение

- включает `:revalidating?`;
- очищает ошибку;
- сохраняет предыдущие данные;
- если состояние было `:idle`, переводит его в `:pending`;
- если состояние уже было `:success`, оставляет `:status :success`;
- активный latest-only запуск, если он был, сбрасывается.

Допустимые ключи `opts`:

- `:started-at`

## `succeed!`

Фиксирует успешное завершение запроса.

```clojure
(request-state/succeed! state
                        {:items [{:id "slot-1"}]}
                        {:request-id "rid-1"
                         :correlation-id "cid-1"
                         :finished-at 1710000010})
```

### Что обновляет

- `:status` -> `:success`
- `:data`
- `:error` -> `nil`
- `:revalidating?` -> `false`
- `:finished-at`
- `:last-success-at`
- `:request-id`
- `:correlation-id`
- `:retry-after-sec`

Обычный успешный переход также сбрасывает активный latest-only запуск.

`response` может быть `nil` или картой.

## `fail!`

Фиксирует ошибку запроса.

```clojure
(request-state/fail! state
                     {:kind :timeout
                      :message "Request timed out"})
```

### Что обновляет

- `:status` -> `:error`
- `:error`
- `:revalidating?` -> `false`
- `:finished-at`
- при наличии:
  - `:request-id`
  - `:correlation-id`
  - `:retry-after-sec`

При этом библиотека не стирает `:data` автоматически.

Обычный ошибочный переход также сбрасывает активный latest-only запуск.

`error` должен быть картой.

## `cancel!`

Фиксирует отмену запроса.

```clojure
(request-state/cancel! state)
```

### Поведение

- `:status` -> `:cancelled`
- `:error` очищается;
- `:data` сохраняется;
- `:revalidating?` -> `false`;
- `:finished-at` обновляется;
- активный latest-only запуск сбрасывается.

Это сделано специально, чтобы latest-only и ручной abort не ломали уже
имеющиеся данные.

## `begin-run!` и `complete-run!`

Это пара функций для latest-only дисциплины.

### `begin-run!`

Создает новый `run-id`, сохраняет его в состоянии и переводит запрос в
`pending`.

```clojure
(def run-id
  (request-state/begin-run! state))
```

### `complete-run!`

Завершает только тот запуск, чей `run-id` совпадает с текущим активным
`run-id`.

```clojure
(request-state/complete-run! state
                             run-id
                             {:status :success
                              :data {:items [1 2 3]}
                              :response {:request-id "rid-2"}})
```

Если завершился более старый запуск, библиотека оставит состояние без
изменений.

Если после `begin-run!` уже произошел обычный переход через `start!`,
`start-refresh!`, `succeed!`, `fail!` или `cancel!`, старый `run-id` тоже не
получит права завершить state.

### Допустимые результаты

`result` должен иметь один из статусов:

- `:success`
- `:error`
- `:cancelled`

Примеры:

```clojure
{:status :success
 :data {:items [1 2 3]}
 :response {:request-id "rid-2"}}
```

```clojure
{:status :error
 :error {:kind :transport-error
         :message "network down"}}
```

```clojure
{:status :cancelled}
```

### Дополнительные правила

Для `result` разрешены только ключи:

- `:status`
- `:data`
- `:error`
- `:response`
- `:finished-at`

Для `:status :error`:

- ключ `:error` обязателен;
- `:error` должен быть картой.

Для `:status :success`:

- `:response` не обязателен;
- если `:response` передан, он должен быть картой.

## Пример модульного использования

У модуля могут быть два независимых request-flow:

```clojure
(def catalog-load-state
  (atom (request-state/init-state)))

(def booking-create-state
  (atom (request-state/init-state)))
```

Загрузка списка:

```clojure
(request-state/start! catalog-load-state)
(request-state/succeed! catalog-load-state
                        {:items [{:id "slot-09-00"}]}
                        response)
```

Отправка действия:

```clojure
(request-state/start! booking-create-state)
(request-state/fail! booking-create-state error)
```

Именно такая модель здесь считается штатной:

- отдельный `atom` на отдельный request-flow;
- библиотека не предполагает один общий request-state на весь модуль.

## Требования к аргументам

Библиотека ожидает:

- `state` как `atom`;
- `opts`, `response`, `error`, `result` как `map`, если они переданы;
- только допустимые ключи в `init-state`, `start!`, `start-refresh!`,
  `complete-run!`.

При нарушении этих правил бросается `ExceptionInfo` с
`:reason :invalid-argument`.

## Что должно остаться у модуля

Даже при использовании `lcmf-request-state` модуль сам решает:

- когда делать первую загрузку;
- когда нужен refresh;
- когда выполнять latest-only сценарий;
- что считать устаревшими данными;
- какие события публиковать в `bus`;
- как совмещать HTTP, websocket и локальные переходы.
