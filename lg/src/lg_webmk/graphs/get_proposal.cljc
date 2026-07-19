(ns lg-webmk.graphs.get-proposal
  "webmk `get_proposal` graph — fetch proposal by ID. clj port of get_proposal.py.

  NSID: com.etzhayyim.apps.webmk.getProposal
  RW→store seam."
  (:require [langgraph.graph :as g]
            [clojure.string :as str]
            [lg-webmk.store :as store]))

(defn fetch [state]
  (let [proposal-id (:proposal-id state "")]
    (cond
      (str/blank? proposal-id) {:ok false :error "proposal_id required"}
      (not (store/enabled?))   {:ok false :error "store not configured"}
      :else
      (if-let [row (store/get-proposal proposal-id)]
        {:ok true
         :proposal {:proposalId (:proposal-id row)
                    :clientName (:client-name row)
                    :websiteUrl (:website-url row)
                    :industry (:industry row)
                    :targetAudience (:target-audience row)
                    :budgetJpy (:budget-jpy row)
                    :qualityScore (double (or (:quality-score row) 0))
                    :status (:status row)
                    :createdAt (str (:created-at row))}}
        {:ok false :error "notFound" :proposal {}}))))

(defn build []
  (-> (g/state-graph)
      (g/add-node :fetch fetch)
      (g/set-entry-point :fetch)
      (g/set-finish-point :fetch)
      (g/compile-graph)))

(def GRAPH (build))
