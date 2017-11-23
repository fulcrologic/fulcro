(ns fulcro-devguide.ZZ-HTML-to-CLJS
  (:require
    [fulcro.client.dom :as dom]
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [fulcro.client.primitives :as prim :refer [defui]]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.core :as fc]
    [fulcro.ui.forms :as f]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [goog.events :as events]
    [fulcro.client.network :as net]
    [clojure.string :as str]
    [hickory.core :as hc]
    [fulcro.ui.file-upload :refer [FileUploadInput file-upload-input file-upload-networking]]
    [fulcro.client.logging :as log]
    [fulcro.ui.bootstrap3 :as b]
    [clojure.set :as set]
    [devcards.util.edn-renderer :as edn]))

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
      (swap! state assoc-in [:top :conv :cljs] cljs))))

(defui HTMLConverter
  static fc/InitialAppState
  (initial-state [clz params] {:html "<div></div>" :cljs (list)})
  static prim/IQuery
  (query [this] [:cljs :html])
  static prim/Ident
  (ident [this p] [:top :conv])
  Object
  (render [this]
    (let [{:keys [html cljs]} (prim/props this)]
      (dom/div #js {:className ""}
        (dom/textarea #js {:cols     80 :rows 10
                           :onChange (fn [evt] (m/set-string! this :html :event evt))
                           :value    html})
        (dom/div #js {} (edn/html-edn cljs))
        (dom/button #js {:className "c-button" :onClick (fn [evt]
                                                          (prim/transact! this `[(convert {})]))} "Convert")))))

(def ui-html-convert (prim/factory HTMLConverter))

(defui HTMLConverterApp
  static fc/InitialAppState
  (initial-state [clz params] {:converter (fc/initial-state HTMLConverter {})})
  static prim/IQuery
  (query [this] [{:converter (prim/get-query HTMLConverter)} :react-key])
  Object
  (render [this]
    (let [{:keys [converter ui/react-key]} (prim/props this)]
      (dom/div
        #js {:key react-key} (ui-html-convert converter)))))

(defcard html-converter
  "The input below can be used to convert raw HTML into DOM code in CLJS. Simply paste in valid HTML and press the button.
  Then copy/paste the result into an editor and reformat. The converter will convert space text nodes into literal
  quoted spaces, but other than that is does a pretty effective job. If there are React attributes that get mis-translated
  then edit this file and add a mapping to the `attr-renames` map."
  (fulcro-app HTMLConverterApp))
