(ns lg-webmk.graphs.list-proposals
  "webmk `list_proposals` graph — paginated proposal list. clj port of
  list_proposals.py.

  NSID: com.etzhayyim.apps.webmk.listProposals
  RW→store seam."
  (:require [langgraph.graph :as g]
            [lg-webmk.store :as store]))

(defn list-node [state]
  (let [limit (min (int (or (:limit state) 50)) 100)
        offset (max (int (or (:offset state) 0)) 0)
        status-filter (:status state)]
    (if-not (store/enabled?)
      {:ok true :items [] :total 0 :limit limit :offset offset}
      (let [rows (store/list-proposals {:limit limit :offset offset :status status-filter})
            items (mapv (fn [r]
                          {:proposalId (:proposal-id r)
                           :clientName (:client-name r)
                           :industry (:industry r)
                           :qualityScore (double (or (:quality-score r) 0))
                           :status (:status r)
                           :createdAt (str (:created-at r))})
                        rows)]
        {:ok true :items items :total (count items) :limit limit :offset offset}))))

(defn build []
  (-> (g/state-graph)
      (g/add-node :list list-node)
      (g/set-entry-point :list)
      (g/set-finish-point :list)
      (g/compile-graph)))

(def GRAPH (build))
