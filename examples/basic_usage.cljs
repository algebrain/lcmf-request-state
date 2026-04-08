(ns examples.basic-usage
  (:require [lcmf.request-state :as request-state]))

(def catalog-load-state
  (atom (request-state/init-state)))

(def booking-create-state
  (atom (request-state/init-state)))

(defn load-catalog-start! []
  (request-state/start! catalog-load-state))

(defn load-catalog-success! [response]
  (request-state/succeed! catalog-load-state
                          (:body response)
                          response))

(defn load-catalog-failure! [error]
  (request-state/fail! catalog-load-state
                       error))

(defn load-catalog-cancelled! []
  (request-state/cancel! catalog-load-state))

(defn refresh-catalog-start! []
  (request-state/start-refresh! catalog-load-state))

(defn create-booking-start! []
  (request-state/start! booking-create-state))

(defn create-booking-success! [response]
  (request-state/succeed! booking-create-state
                          (:body response)
                          response))

(defn create-booking-failure! [error]
  (request-state/fail! booking-create-state
                       error))

(defn latest-only-catalog-refresh! []
  (let [run-id (request-state/begin-run! catalog-load-state)]
    (request-state/complete-run! catalog-load-state
                                 run-id
                                 {:status :success
                                  :data {:items [{:id "slot-1"}]}
                                  :response {:request-id "rid-1"
                                             :correlation-id "cid-1"}})))
