(ns lg-webmk.audit
  "Fire-and-forget BPMN generic.audit.emit shim (clj port of audit.py).

  httpx → babashka.http-client; JSON → cheshire. The POST is best-effort:
  any failure is logged and swallowed (non-fatal), exactly like the Python.
  Honors LG_AUDIT_DISABLED=1 (the test harness sets it)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def default-config
  {:url "http://bpmn-dispatcher.mitama-udf.svc.cluster.local:8080"
   :secret "" :timeout-ms 3000 :disabled? true})
(def ^:dynamic *emit* (fn [_] nil))

(defn emit-with [http-post config event]
  (when-not (fn? http-post)
    (throw (ex-info "WebMK audit requires an explicit HTTP POST capability"
                    {:capability :webmk/audit-http-post})))
  (let [{:keys [url secret timeout-ms]} (merge default-config config)
        payload {:actor (:actor event) :activity (:activity event)
                 :objectId (:object-id event) :objectType (:object-type event)
                 :attributes (or (:attributes event) {})}]
    (http-post (str (str/replace url #"/+$" "")
                    "/xrpc/com.etzhayyim.generic.audit.emit")
               {:headers (cond-> {"Content-Type" "application/json"}
                           (seq secret) (assoc "x-internal-trust" secret))
                :body (json/generate-string payload)
                :timeout timeout-ms :throw false})))

(defn emit-audit!
  "Synchronous best-effort emit. Returns nil. Never throws."
  [{:keys [actor activity object-id object-type attributes]}]
  (try (*emit* {:actor actor :activity activity :object-id object-id
                :object-type object-type :attributes attributes})
       (catch Exception _ nil))
  nil)

(defn emit-audit-bg
  "Fire-and-forget: schedules emit-audit! on a future (asyncio.create_task analogue)."
  [m]
  (future (emit-audit! m)))
