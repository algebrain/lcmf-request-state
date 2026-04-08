(ns lcmf.request-state)

(def ^:private valid-statuses
  #{:idle :pending :success :error :cancelled})

(def ^:private valid-state-keys
  #{:status
    :data
    :error
    :revalidating?
    :started-at
    :finished-at
    :last-success-at
    :request-id
    :correlation-id
    :retry-after-sec
    :current-run-id
    :last-completed-run-id})

(def ^:private valid-start-keys
  #{:started-at
    :data})

(def ^:private valid-start-refresh-keys
  #{:started-at})

(def ^:private valid-complete-run-keys
  #{:status
    :data
    :error
    :response
    :finished-at})

(defn- now-ms []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- raise!
  [message data]
  (throw (ex-info message data)))

(defn- ensure-atom!
  [value field]
  (when-not (instance? #?(:clj clojure.lang.IAtom :cljs cljs.core/Atom) value)
    (raise! (str (name field) " must be atom")
            {:reason :invalid-argument
             :field field
             :value value})))

(defn- ensure-map!
  [value field]
  (when-not (map? value)
    (raise! (str (name field) " must be map")
            {:reason :invalid-argument
             :field field
             :value value})))

(defn- ensure-status!
  [value field]
  (when-not (contains? valid-statuses value)
    (raise! (str (name field) " must be one of " valid-statuses)
            {:reason :invalid-argument
             :field field
             :value value})))

(defn- ensure-allowed-keys!
  [value allowed field]
  (doseq [k (keys value)]
    (when-not (contains? allowed k)
      (raise! (str (name field) " contains unsupported key")
              {:reason :invalid-argument
               :field field
               :key k
               :value value}))))

(defn- body-field [body field]
  (when (map? body)
    (or (get body field)
        (get body (name field)))))

(defn- response-meta [response]
  {:request-id (body-field response :request-id)
   :correlation-id (body-field response :correlation-id)
   :retry-after-sec (body-field response :retry-after-sec)
   :started-at (body-field response :started-at)
   :finished-at (body-field response :finished-at)
   :duration-ms (body-field response :duration-ms)})

(defn init-state
  ([] (init-state {}))
  ([opts]
   (when (some? opts)
     (ensure-map! opts :opts))
   (ensure-allowed-keys! opts valid-state-keys :opts)
   (when (contains? opts :status)
     (ensure-status! (:status opts) :status))
   (merge {:status :idle
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
          opts)))

(defn- update-state!
  [state-atom f & args]
  (ensure-atom! state-atom :state)
  (apply swap! state-atom f args))

(defn- clear-run-bookkeeping
  [state]
  (assoc state :current-run-id nil))

(defn start!
  ([state-atom]
   (start! state-atom {}))
  ([state-atom opts]
   (when (some? opts)
     (ensure-map! opts :opts))
   (ensure-allowed-keys! opts valid-start-keys :opts)
   (let [started-at (or (:started-at opts) (now-ms))]
     (update-state! state-atom
                    (fn [state]
                      (-> state
                          clear-run-bookkeeping
                          (assoc :status :pending
                                 :error nil
                                 :revalidating? false
                                 :started-at started-at
                                 :finished-at nil)
                          (cond-> (contains? opts :data)
                            (assoc :data (:data opts)))))))))

(defn start-refresh!
  ([state-atom]
   (start-refresh! state-atom {}))
  ([state-atom opts]
   (when (some? opts)
     (ensure-map! opts :opts))
   (ensure-allowed-keys! opts valid-start-refresh-keys :opts)
   (let [started-at (or (:started-at opts) (now-ms))]
     (update-state! state-atom
                    (fn [state]
                      (-> state
                          clear-run-bookkeeping
                          (assoc :revalidating? true
                                 :error nil
                                 :started-at started-at
                                 :finished-at nil)
                          (cond-> (= :idle (:status state))
                            (assoc :status :pending))))))))

(defn succeed!
  [state-atom data response]
  (when (some? response)
    (ensure-map! response :response))
  (let [meta (response-meta response)
        finished-at (or (:finished-at meta) (now-ms))]
    (update-state! state-atom
                   (fn [state]
                     (-> state
                         clear-run-bookkeeping
                         (assoc :status :success
                                :data data
                                :error nil
                                :revalidating? false
                                :finished-at finished-at
                                :last-success-at finished-at
                                :request-id (:request-id meta)
                                :correlation-id (:correlation-id meta)
                                :retry-after-sec (:retry-after-sec meta))
                         (cond-> (:started-at meta)
                           (assoc :started-at (:started-at meta))))))))

(defn fail!
  [state-atom error]
  (ensure-map! error :error)
  (let [finished-at (or (:finished-at error) (now-ms))]
    (update-state! state-atom
                   (fn [state]
                     (-> state
                         clear-run-bookkeeping
                         (assoc :status :error
                                :error error
                                :revalidating? false
                                :finished-at finished-at
                                :request-id (:request-id error)
                                :correlation-id (:correlation-id error)
                                :retry-after-sec (:retry-after-sec error)))))))

(defn cancel!
  [state-atom]
  (let [finished-at (now-ms)]
    (update-state! state-atom
                   (fn [state]
                     (-> state
                         clear-run-bookkeeping
                         (assoc :status :cancelled
                                :error nil
                                :revalidating? false
                                :finished-at finished-at))))))

(defn begin-run!
  [state-atom]
  (let [run-id (random-uuid)
        started-at (now-ms)]
    (update-state! state-atom
                   (fn [state]
                     (-> state
                         (assoc :status :pending
                                :error nil
                                :revalidating? false
                                :started-at started-at
                                :finished-at nil
                                :current-run-id run-id))))
    run-id))

(defn complete-run!
  [state-atom run-id result]
  (when-not run-id
    (raise! "run-id must be present"
            {:reason :invalid-argument
             :field :run-id
             :value run-id}))
  (when (some? result)
    (ensure-map! result :result))
  (ensure-allowed-keys! result valid-complete-run-keys :result)
  (let [status (:status result)]
    (when-not (contains? #{:success :error :cancelled} status)
      (raise! "result :status must be :success, :error, or :cancelled"
              {:reason :invalid-argument
               :field :result
               :value result}))
    (case status
      :success
      (when (contains? result :response)
        (when (some? (:response result))
          (ensure-map! (:response result) :response)))

      :error
      (do
        (when-not (contains? result :error)
          (raise! "error result must include :error"
                  {:reason :invalid-argument
                   :field :result
                   :value result}))
        (ensure-map! (:error result) :error))

      :cancelled
      nil))
  (update-state! state-atom
                 (fn [state]
                   (if (not= run-id (:current-run-id state))
                     state
                     (let [finished-at (or (:finished-at result) (now-ms))
                           next-state (-> state
                                          (assoc :last-completed-run-id run-id
                                                 :current-run-id nil
                                                 :finished-at finished-at
                                                 :revalidating? false))]
                       (case (:status result)
                         :success
                         (let [response (:response result)
                               meta (response-meta response)]
                           (-> next-state
                               (assoc :status :success
                                      :data (:data result)
                                      :error nil
                                      :last-success-at finished-at
                                      :request-id (:request-id meta)
                                      :correlation-id (:correlation-id meta)
                                      :retry-after-sec (:retry-after-sec meta))
                               (cond-> (:started-at meta)
                                 (assoc :started-at (:started-at meta)))))

                         :error
                         (-> next-state
                             (assoc :status :error
                                    :error (:error result)
                                    :request-id (body-field (:error result) :request-id)
                                    :correlation-id (body-field (:error result) :correlation-id)
                                    :retry-after-sec (body-field (:error result) :retry-after-sec)))

                         :cancelled
                         (-> next-state
                             (assoc :status :cancelled
                                    :error nil))))))))
