(ns lg-webmk.llm
  "LLM inference via the Murakumo fleet loopback (ADR-2605215000 / 2606172359):
  the LiteLLM gateway (OpenAI-compatible /v1/chat/completions) over
  babashka.http-client — replacing the Python langchain_anthropic / ChatAnthropic.
  Default model gemma-4-e4b-it (lg/CLAUDE.md). Read-only call, fail-open: any
  error returns nil so the caller falls back to a deterministic template, exactly
  like the Python try/except graceful fallback."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def default-config {:url "http://llm.etzhayyim.com" :api-key ""
                     :model "gemma-4-e4b-it" :timeout-ms 30000})
(def ^:dynamic *http-post* nil)
(def ^:dynamic *config* default-config)

(defn complete
  "Single-prompt chat completion. Returns the assistant text, or nil on any
  failure (the caller then uses its template fallback). max-tokens optional."
  ([prompt] (complete prompt 1024))
  ([prompt max-tokens]
   (if-not (fn? *http-post*)
     nil
     (try
     (let [{:keys [url api-key model timeout-ms]} *config*
           headers (cond-> {"Content-Type" "application/json"}
                     (seq api-key) (assoc "Authorization" (str "Bearer " api-key)))
           body {:model model
                 :max_tokens max-tokens
                 :messages [{:role "user" :content prompt}]}
           resp (*http-post* (str (str/replace url #"/+$" "") "/v1/chat/completions")
                           {:headers headers
                            :body (json/generate-string body)
                            :timeout timeout-ms
                            :throw false})]
       (when (= 200 (:status resp))
         (let [parsed (json/parse-string (:body resp) true)
               txt (get-in parsed [:choices 0 :message :content])]
           (when (and txt (seq (str/trim txt))) txt))))
     (catch Exception _ nil)))))
