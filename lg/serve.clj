#!/usr/bin/env bb
;; lg-webmk — host adapter for the XRPC/runs server.
;;
;;   bb --config bb.edn serve          ; from this directory (lg/)
;;   LG_PORT=8080 bb --config bb.edn serve
;;
;; Why this file exists: `lg-webmk.server/-main` throws on purpose. The server
;; namespace is portable (.cljc) and refuses ambient authority — it will not
;; reach for an HTTP server, an HTTP client, or a store on its own. Every
;; capability has to be handed to it explicitly. This file is that hand-off for
;; the babashka host, and it is the twin of `run_tests.clj`, which does the same
;; wiring for the test suite.
;;
;; Effects reached from here, and nowhere else:
;;   org.httpkit.server   listening socket
;;   babashka.http-client outbound: website research fetch, LLM, Resend, audit
(require '[babashka.http-client :as http]
         '[org.httpkit.server :as httpkit]
         '[lg-webmk.audit :as audit]
         '[lg-webmk.llm :as llm]
         '[lg-webmk.server :as server]
         '[lg-webmk.store :as store]
         '[lg-webmk.graphs.create-proposal :as create-proposal]
         '[lg-webmk.graphs.deliver-proposal :as deliver-proposal]
         '[lg-webmk.graphs.health :as health])

(defn- env [name default] (or (System/getenv name) default))

(def app-did (env "WEBMK_APP_DID" create-proposal/app-did))
(def port (Long/parseLong (env "LG_PORT" "8080")))

(def audit-config
  {:url (env "BPMN_DISPATCHER_INTERNAL_URL" (:url audit/default-config))
   :secret (env "BPMN_DISPATCHER_INTERNAL_SECRET" "")
   :timeout-ms (long (* 1000 (Double/parseDouble (env "LG_AUDIT_TIMEOUT_SEC" "3.0"))))})

(def llm-config
  {:url (env "WEBMK_LLM_URL" (:url llm/default-config))
   :api-key (env "WEBMK_LLM_API_KEY" "")
   :model (env "WEBMK_LLM_MODEL" (:model llm/default-config))
   :timeout-ms (long (* 1000 (Long/parseLong (env "WEBMK_LLM_TIMEOUT" "30"))))})

(def resend-config
  {:url "https://api.resend.com/emails"
   :api-key (env "RESEND_API_KEY" "")
   :from (env "RESEND_FROM" "webmk@etzhayyim.com")})

;; Same gate as the store namespace documents: persistence is off unless asked
;; for. `health` reports store-ok=false while it is off -- that is the store
;; probe answering honestly, not a broken server.
(def store-enabled?
  (or (= "1" (env "WEBMK_STORE_ENABLED" "0"))
      (boolean (seq (or (System/getenv "RW_URL") (System/getenv "LG_CHECKPOINTER_URL"))))))

;; Bindings are established per request, inside the handler, because Clojure
;; `binding` is thread-local and httpkit answers on its own worker threads.
(defn handler [request]
  (binding [audit/*emit* (partial audit/emit-with http/post audit-config)
            llm/*http-post* http/post
            llm/*config* llm-config
            store/*enabled?* store-enabled?
            create-proposal/*http-get* http/get
            create-proposal/app-did app-did
            create-proposal/quality-threshold
            (Double/parseDouble (env "WEBMK_QUALITY_THRESHOLD" "0.7"))
            deliver-proposal/app-did app-did
            deliver-proposal/*http-post* http/post
            deliver-proposal/*resend-config* resend-config
            health/app-did app-did
            server/*api-key* (env "LG_API_KEY" "")]
    (server/handler request)))

;; httpkit binds the wildcard address, not the loopback -- so this says "port
;; N", not "http://127.0.0.1:N". On a host where something else already holds
;; IPv4 :N, this process still binds the IPv6 wildcard and starts without
;; complaint, and a client that resolves to IPv4 reaches the OTHER service. Do
;; not print an address this process has not verified it owns.
(server/run-server-with httpkit/run-server port handler)
(println (str "lg-webmk listening on port " port " (all interfaces)"
              "  (store " (if store-enabled? "enabled" "disabled")
              ", api-key " (if (seq (env "LG_API_KEY" "")) "required" "not required") ")"))
@(promise)
