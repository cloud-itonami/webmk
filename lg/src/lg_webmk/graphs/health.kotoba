(ns lg-webmk.graphs.health
  "webmk `health` graph — store probe + liveness. clj port of health.py.

  NSID: com.etzhayyim.apps.webmk.health

  The Python probes RisingWave over psycopg; the clj port probes the swap-seam
  store (lg-webmk.store) instead — substrate boundary forbids RisingWave."
  (:require [langgraph.graph :as g]
            [lg-webmk.audit :as audit]
            [lg-webmk.store :as store]))

(def ^:dynamic app-did "did:web:webmk.etzhayyim.com")

(defn- now-iso []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'")
           (java.time.ZonedDateTime/now (java.time.ZoneOffset/UTC))))

(defn check-store [_state]
  (let [t (System/nanoTime)
        ok (store/enabled?)]
    (if ok
      {:store-ok true :store-latency-ms (long (/ (- (System/nanoTime) t) 1000000))}
      {:store-ok false :error "store not enabled"})))

(defn summarize [state]
  {:ok (boolean (:store-ok state)) :server-now (now-iso)})

(defn audit-node [state]
  (audit/emit-audit-bg
   {:actor app-did :activity "webmk.health.check"
    :object-id (str "health:" (quot (System/currentTimeMillis) 1000))
    :object-type "webmk.health" :attributes {:ok (:ok state false)}})
  {})

(defn build []
  (-> (g/state-graph)
      (g/add-node :check-store check-store)
      (g/add-node :summarize summarize)
      (g/add-node :audit audit-node)
      (g/set-entry-point :check-store)
      (g/add-edge :check-store :summarize)
      (g/add-edge :summarize :audit)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
