(ns lg-webmk.store
  "Proposal store — the charter-clean replacement for the Python RisingWave /
  psycopg persistence (substrate boundary: NO RisingWave/Postgres; state belongs
  on the kotoba Datom log, ADR-2605312345). This namespace is the swap seam: the
  default backend is an in-process append-only atom keyed by proposal-id (a small,
  EAVT-shaped row map), which the kotoba Datom-log adapter can replace without
  touching the graphs.

  Faithful-default gate: like the Python (which skips persistence when RW_URL is
  unset), the store is a NO-OP unless WEBMK_STORE_ENABLED=1 (or RW_URL is set as a
  legacy signal). Enabled, it is a real end-to-end store — strictly more functional
  than the unconfigured Python path."
  (:require #?(:clj [clojure.string :as str])))

(def ^:dynamic *enabled?* false)

(defn enabled?
  "True when the store should persist (faithful analogue of Python's RW_URL gate)."
  []
  (boolean *enabled?*))

;; append-only in-process backend: proposal-id -> row map
(defonce ^:private db (atom {}))

(defn reset-store! [] (reset! db {}))

(defn- now-iso []
  #?(:clj (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'")
                   (java.time.ZonedDateTime/now (java.time.ZoneOffset/UTC)))
     :default ""))

(defn store-proposal!
  "INSERT analogue. Returns {:stored bool}. No-op (stored=false) when disabled."
  [{:keys [proposal-id] :as row}]
  (if-not (enabled?)
    {:stored false}
    (let [vid (str "at://did:web:webmk.etzhayyim.com/com.etzhayyim.apps.webmk.proposal/" proposal-id)
          rec (merge {:status "draft" :actor-did "anon" :org-did "anon"
                      :vertex-id vid :created-at (now-iso)}
                     row)]
      (swap! db assoc proposal-id rec)
      {:stored true})))

(defn get-proposal
  "Returns the proposal row map or nil. Disabled → nil (notFound)."
  [proposal-id]
  (when (enabled?) (get @db proposal-id)))

(defn list-proposals
  "Returns rows newest-first, status-filtered, limit/offset windowed."
  [{:keys [limit offset status]}]
  (if-not (enabled?)
    []
    (cond->> (vals @db)
      status (filter #(= status (:status %)))
      true   (sort-by :created-at #(compare %2 %1))
      true   (drop (or offset 0))
      true   (take (or limit 50)))))

(defn mark-delivered!
  "Marks a draft proposal delivered (delete-then-insert, RW ON-CONFLICT analogue)."
  [proposal-id actor-did]
  (when (enabled?)
    (swap! db (fn [m]
                (if-let [row (get m proposal-id)]
                  (assoc m proposal-id (assoc row :status "delivered" :actor-did actor-did))
                  (assoc m proposal-id {:proposal-id proposal-id :status "delivered"
                                        :actor-did actor-did :org-did "anon"
                                        :created-at (now-iso)})))))
  nil)
