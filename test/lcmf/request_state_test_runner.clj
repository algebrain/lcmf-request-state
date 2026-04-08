(ns lcmf.request-state-test-runner
  (:require [clojure.test :as t]
            [lcmf.request-state-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'lcmf.request-state-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
