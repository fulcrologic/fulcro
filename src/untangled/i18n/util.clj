(ns untangled.i18n.util
  (:require [clojure.string :as str]
            [clojure.data.json :as json]))


(defn get-file [fname] (slurp fname))

(defn parse-po [acc line]
  (let [context (last (re-matches #"^msgctxt \"(.*)\"" line))
        msgid (last (re-matches #"^msgid \"(.*)\"" line))
        translation (last (re-matches #"^msgstr \"(.*)\"" line))]
    (cond
      context (assoc-in acc [:seen :context] context)
      msgid (assoc-in acc [:seen :id] msgid)
      translation (let [empty-seen {:context "" :id ""}
                        js-key (str (get-in acc [:seen :context]) "|" (get-in acc [:seen :id]))
                        js-obj (assoc (:js-obj acc) js-key translation)]
                    (assoc acc :js-obj js-obj :seen empty-seen))
      true acc)
    ))

(defn map-po-to-translations [fname]
  (let [lines (str/split-lines (get-file fname))]
    (:js-obj (reduce #(parse-po %1 %2)
                     {:seen {:context "" :id ""} :js-obj {}} lines))
    ))

(defn stringify-translations [translations]
  (json/write-str translations))
;(defn stringify-translations [translations]
;  (let [trans-seq (vec (flatten (reduce #(into %1 [(str "\"" (first %2) "\"" ":") (str "\"" (second %2) "\"")]) [] translations)))
;        wrapped-trans-seq (conj (vec (cons "{" trans-seq)) "}")]
;    (str/join " " wrapped-trans-seq))
;  )

(defn write-js-translation-file [fname translations-string]
  (spit fname translations-string)
  )
