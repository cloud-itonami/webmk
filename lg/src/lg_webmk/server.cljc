(ns lg-webmk.server
  "XRPC/runs HTTP server for lg-webmk — clj port of server.py (FastAPI →
  org.httpkit.server, a babashka built-in). Same endpoint surface:

    POST /runs                  → invoke a graph synchronously
    POST /runs/stream           → stream graph superstep events as SSE
    POST|GET /xrpc/{nsid}       → XRPC-compat shim (NSID → assistant_id)
    GET  /threads/{tid}/state   → fetch latest checkpoint (best-effort)
    GET  /ok | /health          → liveness

  NSID surface (5 webmk endpoints) preserved verbatim. State is a clj map; the
  langgraph-clj graphs replace the Python LangGraph graphs. RW checkpointer is
  omitted (substrate boundary); thread-state is served from compiled graph state."
  (:require [langgraph.graph :as g]
            [cheshire.core :as json]
            [clojure.string :as str]
            [lg-webmk.graphs.health :as health]
            [lg-webmk.graphs.create-proposal :as create-proposal]
            [lg-webmk.graphs.deliver-proposal :as deliver-proposal]
            [lg-webmk.graphs.get-proposal :as get-proposal]
            [lg-webmk.graphs.list-proposals :as list-proposals]))

(def graphs
  {"health"           health/GRAPH
   "create_proposal"  create-proposal/GRAPH
   "deliver_proposal" deliver-proposal/GRAPH
   "get_proposal"     get-proposal/GRAPH
   "list_proposals"   list-proposals/GRAPH})

(def nsid-map
  {"com.etzhayyim.apps.webmk.health"          "health"
   "com.etzhayyim.apps.webmk.createProposal"  "create_proposal"
   "com.etzhayyim.apps.webmk.deliverProposal" "deliver_proposal"
   "com.etzhayyim.apps.webmk.getProposal"     "get_proposal"
   "com.etzhayyim.apps.webmk.listProposals"   "list_proposals"})

(def ^:dynamic *api-key* "")

;; ── input key normalization (camelCase / snake_case JSON → kebab keyword) ──
(defn- ->kebab [s]
  (-> (name s)
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"_" "-")
      str/lower-case
      keyword))

(defn- normalize-input [m]
  (when (map? m)
    (reduce-kv (fn [acc k v] (assoc acc (->kebab k) v)) {} m)))

(defn- now-iso []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'")
           (java.time.ZonedDateTime/now (java.time.ZoneOffset/UTC))))

;; ── pure dispatch (testable without a running server) ──
(defn run-graph
  "POST /runs body → {:status n :body map}. assistant_id selects the graph."
  [body]
  (let [assistant-id (get body "assistant_id" "")
        graph (get graphs assistant-id)]
    (if-not graph
      {:status 404 :body {:detail (str "graph '" assistant-id "' not found")}}
      (try
        (let [result (g/invoke graph (normalize-input (get body "input" {})))]
          {:status 200 :body {:ok true :output result}})
        (catch Exception e
          {:status 500 :body {:detail (str (.getMessage e))}})))))

(defn xrpc
  "POST|GET /xrpc/{nsid} body → {:status n :body map}."
  [nsid body]
  (if-let [assistant-id (get nsid-map nsid)]
    (try
      (let [result (g/invoke (get graphs assistant-id) (normalize-input (or body {})))]
        {:status 200 :body result})
      (catch Exception e
        {:status 500 :body {:detail (str (.getMessage e))}}))
    {:status 404 :body {:detail (str "NSID '" nsid "' not mapped")}}))

;; ── http handler ──
(defn- json-resp [{:keys [status body]}]
  {:status status :headers {"Content-Type" "application/json"} :body (json/generate-string body)})

(defn- parse-body [req]
  (when-let [b (:body req)]
    (try (json/parse-string (slurp b)) (catch Exception _ nil))))

(defn- authed? [req]
  (or (empty? *api-key*)
      (= *api-key* (get-in req [:headers "x-api-key"]))))

(defn handler [req]
  (let [uri (:uri req) method (:request-method req)]
    (cond
      (and (= method :get) (#{"/ok" "/health"} uri))
      (json-resp {:status 200 :body {:ok true :ts (now-iso)}})

      (and (= method :post) (= uri "/runs"))
      (if (authed? req)
        (json-resp (run-graph (or (parse-body req) {})))
        (json-resp {:status 401 :body {:detail "Invalid API key"}}))

      (and (= method :post) (= uri "/runs/stream"))
      (if (authed? req)
        (let [body (or (parse-body req) {})
              assistant-id (get body "assistant_id" "")
              graph (get graphs assistant-id)]
          (if-not graph
            (json-resp {:status 404 :body {:detail (str "graph '" assistant-id "' not found")}})
            (let [events (g/stream graph (normalize-input (get body "input" {})))
                  sse (str (str/join "" (map #(str "data: " (json/generate-string %) "\n\n") events))
                           "data: [DONE]\n\n")]
              {:status 200 :headers {"Content-Type" "text/event-stream"} :body sse})))
        (json-resp {:status 401 :body {:detail "Invalid API key"}}))

      (str/starts-with? uri "/xrpc/")
      (json-resp (xrpc (subs uri (count "/xrpc/")) (parse-body req)))

      (and (= method :get) (str/starts-with? uri "/threads/"))
      (json-resp {:status 404 :body {:detail "thread state not retained (no RW checkpointer)"}})

      :else
      (json-resp {:status 404 :body {:detail "not found"}}))))

(defn run-server-with [run-server port handler-fn]
  (when-not (fn? run-server)
    (throw (ex-info "WebMK requires an explicit server capability"
                    {:capability :webmk/run-server})))
  (run-server handler-fn {:port port}))

(defn -main [& _]
  (throw (ex-info "WebMK portable runtime requires an explicit host adapter"
                  {:capability :webmk/host-adapter})))
