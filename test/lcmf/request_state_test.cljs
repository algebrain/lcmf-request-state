(ns lcmf.request-state-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lcmf.request-state :as rs]))

(defn- thrown-data [f]
  (try
    (f)
    nil
    (catch :default ex
      (ex-data ex))))

(deftest init-state-test
  (testing "init-state returns the default shape"
    (let [state (rs/init-state)]
      (is (= {:status :idle
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
             state))))

  (testing "init-state merges user options"
    (let [state (rs/init-state {:data {:items []}
                                :status :success})]
      (is (= :success (:status state)))
      (is (= {:items []} (:data state)))))

  (testing "init-state rejects non-canonical status values"
    (is (= :invalid-argument
           (:reason (thrown-data #(rs/init-state {:status :revalidating})))))))

(deftest start-transition-test
  (testing "start! moves state to pending and clears error"
    (let [state (atom (rs/init-state {:status :error
                                      :error {:kind :http-error}
                                      :data {:items [1]}}))]
      (rs/start! state {:started-at 100})
      (is (= :pending (:status @state)))
      (is (nil? (:error @state)))
      (is (= {:items [1]} (:data @state)))
      (is (= 100 (:started-at @state))))))

(deftest refresh-transition-test
  (testing "start-refresh! keeps data and turns on revalidating"
    (let [state (atom (rs/init-state {:status :success
                                      :data {:items [1]}}))]
      (rs/start-refresh! state {:started-at 200})
      (is (= :success (:status @state)))
      (is (not= :revalidating (:status @state)))
      (is (true? (:revalidating? @state)))
      (is (= {:items [1]} (:data @state)))
      (is (= 200 (:started-at @state)))))

  (testing "start-refresh! on idle turns state to pending"
    (let [state (atom (rs/init-state))]
      (rs/start-refresh! state)
      (is (= :pending (:status @state)))
      (is (true? (:revalidating? @state))))))

(deftest success-transition-test
  (testing "succeed! stores data and safe response metadata"
    (let [state (atom (rs/init-state {:status :pending}))
          response {:request-id "rid-1"
                    :correlation-id "cid-1"
                    :retry-after-sec nil
                    :started-at 100
                    :finished-at 150}]
      (rs/succeed! state {:items [1]} response)
      (is (= :success (:status @state)))
      (is (= {:items [1]} (:data @state)))
      (is (nil? (:error @state)))
      (is (= 100 (:started-at @state)))
      (is (= 150 (:finished-at @state)))
      (is (= 150 (:last-success-at @state)))
      (is (= "rid-1" (:request-id @state)))
      (is (= "cid-1" (:correlation-id @state))))))

(deftest fail-transition-test
  (testing "fail! stores the normalized error"
    (let [state (atom (rs/init-state {:status :pending
                                      :data {:items [1]}}))
          error {:kind :timeout
                 :message "Request timed out"
                 :request-id "rid-2"
                 :correlation-id "cid-2"
                 :retry-after-sec 5
                 :finished-at 300}]
      (rs/fail! state error)
      (is (= :error (:status @state)))
      (is (= error (:error @state)))
      (is (= {:items [1]} (:data @state)))
      (is (= "rid-2" (:request-id @state)))
      (is (= "cid-2" (:correlation-id @state)))
      (is (= 5 (:retry-after-sec @state)))
      (is (= 300 (:finished-at @state))))))

(deftest cancel-transition-test
  (testing "cancel! marks state as cancelled and keeps previous data"
    (let [state (atom (rs/init-state {:status :pending
                                      :data {:items [1]}
                                      :error {:kind :http-error}}))]
      (rs/cancel! state)
      (is (= :cancelled (:status @state)))
      (is (= {:items [1]} (:data @state)))
      (is (nil? (:error @state)))
      (is (number? (:finished-at @state))))))

(deftest latest-only-run-test
  (testing "complete-run! ignores stale run results"
    (let [state (atom (rs/init-state))
          stale-run (rs/begin-run! state)
          fresh-run (rs/begin-run! state)]
      (rs/complete-run! state
                        stale-run
                        {:status :success
                         :data {:items [:stale]}
                         :response {:request-id "rid-stale"}})
      (is (= :pending (:status @state)))
      (is (= fresh-run (:current-run-id @state)))
      (is (nil? (:data @state)))
      (rs/complete-run! state
                        fresh-run
                        {:status :success
                         :data {:items [:fresh]}
                         :response {:request-id "rid-fresh"
                                    :finished-at 500}})
      (is (= :success (:status @state)))
      (is (= {:items [:fresh]} (:data @state)))
      (is (= "rid-fresh" (:request-id @state)))
      (is (= fresh-run (:last-completed-run-id @state)))
      (is (nil? (:current-run-id @state)))))

  (testing "complete-run! supports error and cancelled endings"
    (let [state (atom (rs/init-state))
          run-id (rs/begin-run! state)]
      (rs/complete-run! state
                        run-id
                        {:status :error
                         :error {:kind :transport-error
                                 :message "network down"}})
      (is (= :error (:status @state)))
      (is (= {:kind :transport-error
              :message "network down"}
             (:error @state)))
      (let [run-id-2 (rs/begin-run! state)]
        (rs/complete-run! state
                          run-id-2
                          {:status :cancelled})
        (is (= :cancelled (:status @state)))
        (is (nil? (:error @state)))))))

(deftest invalid-arguments-test
  (testing "state must be atom"
    (is (= :invalid-argument
           (:reason (thrown-data #(rs/start! {}))))))

  (testing "fail! requires map error"
    (is (= :invalid-argument
           (:reason (thrown-data #(rs/fail! (atom (rs/init-state))
                                            :boom))))))

  (testing "init-state rejects unknown keys"
    (is (= :invalid-argument
           (:reason (thrown-data #(rs/init-state {:unknown true}))))))

  (testing "start! rejects unknown option keys"
    (is (= :invalid-argument
           (:reason (thrown-data #(rs/start! (atom (rs/init-state))
                                             {:started-at 10
                                              :unknown true}))))))

  (testing "start-refresh! rejects unknown option keys"
    (is (= :invalid-argument
           (:reason (thrown-data #(rs/start-refresh! (atom (rs/init-state))
                                                     {:unknown true}))))))

  (testing "complete-run! error result requires map error payload"
    (let [state (atom (rs/init-state))
          run-id (rs/begin-run! state)]
      (is (= :invalid-argument
             (:reason (thrown-data #(rs/complete-run! state run-id {:status :error})))))
      (is (= :invalid-argument
             (:reason (thrown-data #(rs/complete-run! state run-id {:status :error
                                                                    :error :boom})))))))

  (testing "complete-run! success accepts nil response"
    (let [state (atom (rs/init-state))
          run-id (rs/begin-run! state)]
      (rs/complete-run! state run-id {:status :success
                                      :data {:items [1 2 3]}})
      (is (= :success (:status @state)))
      (is (= {:items [1 2 3]} (:data @state)))))

  (testing "complete-run! success rejects non-map response"
    (let [state (atom (rs/init-state))
          run-id (rs/begin-run! state)]
      (is (= :invalid-argument
             (:reason (thrown-data #(rs/complete-run! state run-id {:status :success
                                                                    :data {:items []}
                                                                    :response :boom}))))))))

(deftest single-flow-api-test
  (testing "single-flow state works without operation identity"
    (let [state (atom (rs/init-state))]
      (rs/start! state)
      (rs/succeed! state {:items [1 2 3]} {:request-id "rid-1"})
      (is (= :success (:status @state)))
      (is (= {:items [1 2 3]} (:data @state)))
      (is (= "rid-1" (:request-id @state)))))

  (testing "single-flow latest-only state works without operation identity"
    (let [state (atom (rs/init-state))
          run-id (rs/begin-run! state)]
      (rs/complete-run! state run-id {:status :cancelled})
      (is (= :cancelled (:status @state)))
      (is (nil? (:current-run-id @state)))))

  (testing "state shape does not expose inter-operation identity"
    (let [state (rs/init-state)]
      (is (not (contains? state :request-key)))))

  (testing "canonical state uses status plus revalidating flag"
    (let [state (atom (rs/init-state {:status :success
                                      :data {:items [:cached]}}))]
      (rs/start-refresh! state)
      (is (= :success (:status @state)))
      (is (true? (:revalidating? @state)))
      (is (not= :revalidating (:status @state))))))

(deftest latest-only-safety-test
  (testing "plain success clears active run id and blocks stale completion"
    (let [state (atom (rs/init-state))
          run-id (rs/begin-run! state)]
      (rs/succeed! state {:items [:plain]} {:request-id "rid-plain"})
      (is (nil? (:current-run-id @state)))
      (rs/complete-run! state run-id {:status :success
                                      :data {:items [:stale]}
                                      :response {:request-id "rid-stale"}})
      (is (= :success (:status @state)))
      (is (= {:items [:plain]} (:data @state)))
      (is (= "rid-plain" (:request-id @state)))))

  (testing "plain failure clears active run id and blocks stale completion"
    (let [state (atom (rs/init-state))
          run-id (rs/begin-run! state)
          error {:kind :transport-error
                 :message "network down"}]
      (rs/fail! state error)
      (is (nil? (:current-run-id @state)))
      (rs/complete-run! state run-id {:status :success
                                      :data {:items [:stale]}})
      (is (= :error (:status @state)))
      (is (= error (:error @state)))))

  (testing "plain cancel clears active run id and blocks stale completion"
    (let [state (atom (rs/init-state {:data {:items [:kept]}}))
          run-id (rs/begin-run! state)]
      (rs/cancel! state)
      (is (nil? (:current-run-id @state)))
      (rs/complete-run! state run-id {:status :success
                                      :data {:items [:stale]}})
      (is (= :cancelled (:status @state)))
      (is (= {:items [:kept]} (:data @state)))))

  (testing "plain start clears previous run id before a new ordinary lifecycle"
    (let [state (atom (rs/init-state))
          run-id (rs/begin-run! state)]
      (rs/start! state)
      (is (nil? (:current-run-id @state)))
      (rs/complete-run! state run-id {:status :success
                                      :data {:items [:stale]}})
      (is (= :pending (:status @state)))
      (is (nil? (:data @state)))))

  (testing "refresh start clears previous run id before ordinary refresh lifecycle"
    (let [state (atom (rs/init-state {:status :success
                                      :data {:items [:fresh]}}))
          run-id (rs/begin-run! state)]
      (rs/start-refresh! state)
      (is (nil? (:current-run-id @state)))
      (rs/complete-run! state run-id {:status :error
                                      :error {:kind :timeout
                                              :message "stale timeout"}})
      (is (= :pending (:status @state)))
      (is (true? (:revalidating? @state)))
      (is (= {:items [:fresh]} (:data @state))))))

(deftest module-level-usage-test
  (testing "module can keep separate request flows for list loading and form submit"
    (let [catalog-load-state (atom (rs/init-state))
          booking-create-state (atom (rs/init-state))
          catalog-response {:request-id "rid-catalog"
                            :correlation-id "cid-catalog"
                            :finished-at 120}
          booking-error {:kind :http-error
                         :status 409
                         :code "slot_not_open"
                         :message "Slot not open"
                         :request-id "rid-booking"
                         :correlation-id "cid-booking"}]
      (rs/start! catalog-load-state)
      (rs/succeed! catalog-load-state
                   {:items [{:id "slot-09-00"}
                            {:id "slot-10-00"}]}
                   catalog-response)
      (rs/start! booking-create-state)
      (rs/fail! booking-create-state booking-error)
      (is (= :success (:status @catalog-load-state)))
      (is (= {:items [{:id "slot-09-00"}
                      {:id "slot-10-00"}]}
             (:data @catalog-load-state)))
      (is (= "rid-catalog" (:request-id @catalog-load-state)))
      (is (= :error (:status @booking-create-state)))
      (is (= booking-error (:error @booking-create-state)))
      (is (nil? (:error @catalog-load-state)))
      (is (nil? (:data @booking-create-state)))))

  (testing "module can use latest-only for list refresh without affecting another flow"
    (let [catalog-load-state (atom (rs/init-state {:status :success
                                                   :data {:items [{:id "slot-09-00"}]}}))
          booking-create-state (atom (rs/init-state))
          stale-run (rs/begin-run! catalog-load-state)
          fresh-run (rs/begin-run! catalog-load-state)]
      (rs/start! booking-create-state)
      (rs/succeed! booking-create-state
                   {:id "booking-1"
                    :slot-id "slot-09-00"}
                   {:request-id "rid-create"})
      (rs/complete-run! catalog-load-state
                        stale-run
                        {:status :success
                         :data {:items [{:id "slot-stale"}]}})
      (rs/complete-run! catalog-load-state
                        fresh-run
                        {:status :success
                         :data {:items [{:id "slot-10-00"}]}
                         :response {:request-id "rid-refresh"}})
      (is (= :success (:status @catalog-load-state)))
      (is (= {:items [{:id "slot-10-00"}]}
             (:data @catalog-load-state)))
      (is (= "rid-refresh" (:request-id @catalog-load-state)))
      (is (= :success (:status @booking-create-state)))
      (is (= {:id "booking-1"
              :slot-id "slot-09-00"}
             (:data @booking-create-state)))
      (is (= "rid-create" (:request-id @booking-create-state))))))
