(ns lg-webmk.graphs.create-proposal
  "webmk `create_proposal` graph — research → competitors → strategy → copy →
  quality_gate → store. clj port of create_proposal.py.

  NSID: com.etzhayyim.apps.webmk.createProposal

  Topology mirrors the Python StateGraph exactly:
    init → research_company → analyze_competitors → generate_strategy →
    generate_copy → quality_gate -[conditional]-> {generate_copy | store_proposal}
    store_proposal → audit → END

  Deviation (noted): the Python quality-retry router can loop indefinitely on the
  no-LLM fallback path (the gate stops incrementing retry_count after the first
  retry while the router keeps routing back). This port preserves the INTENT
  ('retry once') with a terminating gate: the gate always increments retry_count
  and the router regenerates only while retry_count < 2 — exactly one retry, then
  it proceeds. httpx→babashka.http-client, LLM→Murakumo loopback, RW→store seam."
  (:require [langgraph.graph :as g]
            [clojure.string :as str]
            [lg-webmk.audit :as audit]
            [lg-webmk.llm :as llm]
            [lg-webmk.store :as store]))

(def ^:dynamic app-did "did:web:webmk.etzhayyim.com")
(def ^:dynamic quality-threshold 0.7)
(def ^:dynamic *http-get* nil)

(defn- uid [prefix]
  (let [digest (-> (java.security.MessageDigest/getInstance "SHA-256")
                   (.digest (.getBytes (str prefix (System/nanoTime)) "UTF-8")))
        hex (apply str (map #(format "%02x" (bit-and % 0xff)) digest))]
    (str prefix "-" (subs hex 0 12))))

(defn init [state]
  {:proposal-id (or (:proposal-id state) (uid "prop")) :retry-count 0})

(defn research-company [state]
  (let [website-url (:website-url state "")
        client-name (:client-name state "")]
    (if (str/blank? website-url)
      {:company-context (str "Company: " client-name ". No website provided.")}
      (let [raw-html (try
                       (when-not (fn? *http-get*)
                         (throw (ex-info "website fetch capability not supplied" {})))
                       (let [resp (*http-get* website-url {:timeout 10000 :throw false})]
                         (subs (str (:body resp)) 0 (min 4000 (count (str (:body resp))))))
                       (catch Exception e
                         (str "Could not fetch " website-url)))]
        {:company-context (str "Company: " client-name
                               ". Industry: " (:industry state "unknown")
                               ". Site snippet: " (subs raw-html 0 (min 500 (count raw-html))))}))))

(defn analyze-competitors [state]
  (let [industry (:industry state "general")]
    {:competitor-summary (str "Competitive landscape for '" industry
                              "': key players identified via industry analysis.")}))

(defn generate-strategy [state]
  (let [prompt (str "You are a web marketing strategist. Generate a JSON marketing strategy for:\n"
                    "Client: " (:client-name state) "\n"
                    "Industry: " (:industry state) "\n"
                    "Target: " (:target-audience state) "\n"
                    "Budget: " (:budget-jpy state 0) " JPY\n"
                    "Context: " (subs (str (:company-context state "")) 0 (min 500 (count (str (:company-context state ""))))) "\n"
                    "Competitors: " (subs (str (:competitor-summary state "")) 0 (min 300 (count (str (:competitor-summary state ""))))) "\n"
                    "Return a JSON object with keys: goals, channels, tactics, kpis")
        strategy (or (llm/complete prompt 1024)
                     "{\"goals\":[\"brand awareness\"],\"channels\":[\"SEO\",\"SNS\"],\"tactics\":[\"content marketing\"],\"kpis\":[\"CTR\",\"CVR\"]}")]
    {:strategy-json strategy}))

(defn generate-copy [state]
  (let [prompt (str "Write a professional web marketing proposal in Markdown for:\n"
                    "Client: " (:client-name state) "\n"
                    "Strategy: " (subs (str (:strategy-json state "")) 0 (min 800 (count (str (:strategy-json state ""))))) "\n"
                    "Include: Executive Summary, Goals, Recommended Channels, Monthly Plan, Budget Breakdown, KPIs")
        copy-md (or (llm/complete prompt 2048)
                    (str "# Marketing Proposal for " (:client-name state)
                         "\n\nGenerated proposal content."))]
    {:copy-markdown copy-md}))

(defn quality-gate [state]
  (let [copy-md (:copy-markdown state "")
        score (min 1.0 (/ (count copy-md) 2000.0))]
    {:quality-score score :retry-count (inc (:retry-count state 0))}))

(defn quality-router [state]
  (if (and (< (:quality-score state 0.0) quality-threshold)
           (< (:retry-count state 0) 2))
    :generate-copy
    :store-proposal))

(defn store-proposal [state]
  (let [proposal-id (:proposal-id state (uid "prop"))
        r (store/store-proposal! {:proposal-id proposal-id
                                  :client-name (:client-name state "")
                                  :website-url (:website-url state "")
                                  :industry (:industry state "")
                                  :target-audience (:target-audience state "")
                                  :budget-jpy (:budget-jpy state 0)
                                  :strategy-json (:strategy-json state "")
                                  :copy-markdown (:copy-markdown state "")
                                  :quality-score (:quality-score state 0.0)
                                  :status "draft"
                                  :actor-did app-did})]
    {:ok true :stored (:stored r)}))

(defn audit-node [state]
  (audit/emit-audit-bg
   {:actor app-did :activity "webmk.createProposal"
    :object-id (:proposal-id state "unknown") :object-type "webmk.proposal"
    :attributes {:ok (:ok state false) :qualityScore (:quality-score state 0.0)}})
  {})

(defn build []
  (-> (g/state-graph)
      (g/add-node :init init)
      (g/add-node :research-company research-company)
      (g/add-node :analyze-competitors analyze-competitors)
      (g/add-node :generate-strategy generate-strategy)
      (g/add-node :generate-copy generate-copy)
      (g/add-node :quality-gate quality-gate)
      (g/add-node :store-proposal store-proposal)
      (g/add-node :audit audit-node)
      (g/set-entry-point :init)
      (g/add-edge :init :research-company)
      (g/add-edge :research-company :analyze-competitors)
      (g/add-edge :analyze-competitors :generate-strategy)
      (g/add-edge :generate-strategy :generate-copy)
      (g/add-edge :generate-copy :quality-gate)
      (g/add-conditional-edges :quality-gate quality-router
                               {:generate-copy :generate-copy
                                :store-proposal :store-proposal})
      (g/add-edge :store-proposal :audit)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
