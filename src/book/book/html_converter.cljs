(ns book.html-converter
  (:require
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [hickory.core :as hc]
    [com.rpl.specter :as sp]
    [clojure.set :as set]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]))

(def attr-renames {
                   :class        :className
                   :for          :htmlFor
                   :tabindex     :tabIndex
                   :viewbox      :viewBox
                   :spellcheck   :spellcheck
                   :autocorrect  :autoCorrect
                   :autocomplete :autoComplete})

(defn elem-to-cljs [elem]
  (cond
    (and (string? elem)
      (let [elem (str/trim elem)]
        (or
          (= "" elem)
          (and
            (str/starts-with? elem "<!--")
            (str/ends-with? elem "-->"))
          (re-matches #"^[ \n]*$" elem)))) nil
    (string? elem) (str/trim elem)
    (vector? elem) (let [tag      (name (first elem))
                         attrs    (set/rename-keys (second elem) attr-renames)
                         children (keep elem-to-cljs (drop 2 elem))]
                     (concat (list (symbol "dom" tag) attrs) children))
    :otherwise "UNKNOWN"))

(defn to-cljs
  "Convert an HTML fragment (containing just one tag) into a corresponding Dom cljs"
  [html-fragment]
  (let [hiccup-list (map hc/as-hiccup (hc/parse-fragment html-fragment))]
    (first (map elem-to-cljs hiccup-list))))

(defmutation convert [p]
  (action [{:keys [state]}]
    (let [html (get-in @state [:top :conv :html])
          cljs (to-cljs html)]
      (swap! state assoc-in [:top :conv :cljs] {:code cljs}))))

(defsc HTMLConverter [this {:keys [html cljs]}]
  {:initial-state (fn [params] {:html "<div id=\"3\" class=\"b\"><p>Paragraph</p></div>" :cljs {:code (list)}})
   :query         [:cljs :html]
   :ident         (fn [] [:top :conv])}
  (dom/div {:className ""}
    (dom/textarea {:cols     80 :rows 10
                   :onChange (fn [evt] (m/set-string! this :html :event evt))
                   :value    html})
    (dom/pre {} (with-out-str (pprint (:code cljs))))
    (dom/button :.c-button {:onClick (fn [evt]
                                       (prim/transact! this `[(convert {})]))} "Convert")))

(def ui-html-convert (prim/factory HTMLConverter))

(defsc Root [this {:keys [converter]}]
  {:initial-state {:converter {}}
   :query         [{:converter (prim/get-query HTMLConverter)}]}
  (ui-html-convert converter))

