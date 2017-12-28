(ns book.html-converter
  (:require
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [hickory.core :as hc]
    [clojure.set :as set]))

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
    (string? elem) elem
    (vector? elem) (let [tag      (name (first elem))
                         attrs    (set/rename-keys (second elem) attr-renames)
                         children (map elem-to-cljs (rest (rest elem)))]
                     (concat (list (symbol "dom" tag) (symbol "#js") attrs) children))
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
  (dom/div #js {:className ""}
    (dom/textarea #js {:cols     80 :rows 10
                       :onChange (fn [evt] (m/set-string! this :html :event evt))
                       :value    html})
    (dom/div #js {} (pr-str (:code cljs)))
    (dom/button #js {:className "c-button" :onClick (fn [evt]
                                                      (prim/transact! this `[(convert {})]))} "Convert")))

(def ui-html-convert (prim/factory HTMLConverter))

(defsc Root [this {:keys [converter]}]
  {:initial-state {:converter {}}
   :query         [{:converter (prim/get-query HTMLConverter)}]}
  (dom/div nil (ui-html-convert converter)))

