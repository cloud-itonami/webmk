(ns lg-webmk.graphs.deliver-proposal
  "webmk `deliver_proposal` graph — send proposal via Resend email. clj port of
  deliver_proposal.py.

  NSID: com.etzhayyim.apps.webmk.deliverProposal

  Topology: fetch_proposal → send_email → update_status → audit → END.
  httpx→babashka.http-client (Resend REST), RW→store seam."
  (:require [langgraph.graph :as g]
            [cheshire.core :as json]
            [clojure.string :as str]
            [lg-webmk.audit :as audit]
            [lg-webmk.store :as store]))

(def ^:dynamic app-did "did:web:webmk.etzhayyim.com")
(def ^:dynamic *resend-config* {:url "https://api.resend.com/emails"
                                :api-key "" :from "webmk@etzhayyim.com"})
(def ^:dynamic *http-post* nil)

(defn fetch-proposal [state]
  (if (seq (:copy-markdown state ""))
    {}
    (let [proposal-id (:proposal-id state "")]
      (if (str/blank? proposal-id)
        {:copy-markdown ""}
        {:copy-markdown (or (:copy-markdown (store/get-proposal proposal-id)) "")}))))

(defn send-email [state]
  (let [{:keys [url api-key from]} *resend-config*
        delivery-email (:delivery-email state "")
        copy-markdown (:copy-markdown state "")
        proposal-id (:proposal-id state "unknown")]
    (cond
      (str/blank? delivery-email)
      {:ok false :delivered false :error "delivery_email not set"}

      (or (str/blank? api-key) (not (fn? *http-post*)))
      {:ok true :delivered false}

      :else
      (try
        (let [resp (*http-post* url
                              {:headers {"Authorization" (str "Bearer " api-key)
                                         "Content-Type" "application/json"}
                               :body (json/generate-string
                                      {:from from
                                       :to [delivery-email]
                                       :subject (str "[webmk] Marketing Proposal #" proposal-id)
                                       :text (subs copy-markdown 0 (min 10000 (count copy-markdown)))})
                               :timeout 15000
                               :throw false})]
          (if (<= 200 (:status resp) 299)
            {:ok true :delivered true}
            {:ok false :delivered false :error (str "resend status " (:status resp))}))
        (catch Exception e
          {:ok false :delivered false :error (subs (str (.getMessage e)) 0 (min 200 (count (str (.getMessage e)))))})))))

(defn update-status [state]
  (when (:delivered state)
    (let [proposal-id (:proposal-id state "")]
      (when-not (str/blank? proposal-id)
        (store/mark-delivered! proposal-id app-did))))
  {})

(defn audit-node [state]
  (audit/emit-audit-bg
   {:actor app-did :activity "webmk.deliverProposal"
    :object-id (:proposal-id state "unknown") :object-type "webmk.proposal"
    :attributes {:delivered (:delivered state false)}})
  {})

(defn build []
  (-> (g/state-graph)
      (g/add-node :fetch-proposal fetch-proposal)
      (g/add-node :send-email send-email)
      (g/add-node :update-status update-status)
      (g/add-node :audit audit-node)
      (g/set-entry-point :fetch-proposal)
      (g/add-edge :fetch-proposal :send-email)
      (g/add-edge :send-email :update-status)
      (g/add-edge :update-status :audit)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
