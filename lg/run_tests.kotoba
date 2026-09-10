#!/usr/bin/env bb
;; lg-webmk — bb-native test runner (Clojure / babashka; NOT shell, per the
;; repo-wide rule "Operational code = clj/bb", ADR-2606072802 + 2606280030).
;;
;;   bb --config 60-apps/etzhayyim-project-webmk/lg/bb.edn test
;;   # or directly (resolves langgraph-clj via the same bb.edn):
;;   bb --config 60-apps/etzhayyim-project-webmk/lg/bb.edn run_tests.clj
;;
;; Audit emit is disabled here so no background HTTP fires during tests.
(require '[babashka.http-client :as http]
         '[clojure.test :as t]
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
(def store-enabled?
  (or (= "1" (env "WEBMK_STORE_ENABLED" "0"))
      (seq (or (System/getenv "RW_URL") (System/getenv "LG_CHECKPOINTER_URL")))))

(defn handler [request]
  (binding [audit/*emit* (partial audit/emit-with http/post audit-config)
            llm/*http-post* http/post llm/*config* llm-config
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

(defn start-server! [port]
  (server/run-server-with httpkit/run-server port handler))

(when-not (= "1" (System/getenv "LG_AUDIT_DISABLED"))
  (println "note: set LG_AUDIT_DISABLED=1 to silence background audit posts"))

(require 'lg-webmk.test-smoke)

(let [{:keys [fail error]} (t/run-tests 'lg-webmk.test-smoke)]
  (if (zero? (+ fail error))
    (println "── lg-webmk: ALL suites green ──")
    (do (println "── lg-webmk: FAILURES above ──")
        (System/exit 1))))
