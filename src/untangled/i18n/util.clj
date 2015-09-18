(ns untangled.i18n.util
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]))


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
                        js-obj (assoc (:cljs-obj acc) js-key translation)]
                    (assoc acc :cljs-obj js-obj :seen empty-seen))
      true acc)
    ))

(defn map-po-to-translations [fname]
  (let [lines (str/split-lines (get-file fname))]
    (:cljs-obj (reduce #(parse-po %1 %2)
                       {:seen {:context "" :id "" :id-value-acc [] :trans-value-acc []} :cljs-obj {}} lines))
    ))

(defn wrap-with-swap [& {:keys [locale translation]}]
  (let [trans-namespace (symbol (str "untangled.translations." locale))
        ns-decl (pp/write (list 'ns trans-namespace) :stream nil)
        trans-def (pp/write (list 'def 'translations translation) :stream nil)
        swap-decl (pp/write (list 'swap! 'untangled.i18n.loaded-translations
                                  (list 'fn '[x] (list 'assoc 'x locale 'translations))) :stream nil)]
    (str/join "\n\n" [ns-decl trans-def swap-decl])))


(defn write-cljs-translation-file [fname translations-string]
  (spit fname translations-string)
  )

; TODO: below is the basis for multiline translation support, still needs implementation
;
;(let [fstring (slurp "/Users/Dave/projects/survey/i18n/msgs/fr_CA.po")
;      trans-chunks (rest (clojure.string/split fstring #"(?ms)\n\n"))
;      grouped-chunks (map clojure.string/split-lines trans-chunks)
;      comment? #(re-matches #"^#.*" %)
;      uncommented-chunks (map #(remove comment? %) grouped-chunks)
;
;      keyed-chunk #(reduce (fn [acc line]
;                             (if (re-matches #"^msg.*" line) (conj acc [line])
;                                                             (update-in acc [(dec (count acc))] conj line)))
;                           [] %)
;
;      keyed-chunks (map keyed-chunk uncommented-chunks)
;
;      ] keyed-chunks)
