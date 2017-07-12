(ns fulcro-devguide.ZZ-HTML-to-CLJS
  (:require
    [om.dom :as dom]
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [om.next :as om :refer [defui]]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.core :as uc]
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
  "Convert an HTML fragment (containing just one tag) into a corresponding Om Dom cljs"
  [html-fragment]
  (let [hiccup-list (map hc/as-hiccup (hc/parse-fragment html-fragment))]
    (first (map elem-to-cljs hiccup-list))))

(defmutation convert [p]
  (action [{:keys [state]}]
    (let [html (get-in @state [:top :conv :html])
          cljs (to-cljs html)]
      (swap! state assoc-in [:top :conv :cljs] cljs))))

(defui HTMLConverter
  static uc/InitialAppState
  (initial-state [clz params] {:html "<div></div>" :cljs (list)})
  static om/IQuery
  (query [this] [:cljs :html])
  static om/Ident
  (ident [this p] [:top :conv])
  Object
  (render [this]
    (let [{:keys [html cljs]} (om/props this)]
      (dom/div #js {:className ""}
        (dom/textarea #js {:cols     80 :rows 10
                           :onChange (fn [evt] (m/set-string! this :html :event evt))
                           :value    html})
        (dom/div #js {} (edn/html-edn cljs))
        (dom/button #js {:className "c-button" :onClick (fn [evt]
                                                          (om/transact! this `[(convert {})]))} "Convert")))))

(def ui-html-convert (om/factory HTMLConverter))

(defui HTMLConverterApp
  static uc/InitialAppState
  (initial-state [clz params] {:converter (uc/initial-state HTMLConverter {})})
  static om/IQuery
  (query [this] [{:converter (om/get-query HTMLConverter)} :react-key])
  Object
  (render [this]
    (let [{:keys [converter ui/react-key]} (om/props this)]
      (dom/div
        #js {:key react-key} (ui-html-convert converter)))))

(defcard html-converter
  "The input below can be used to convert raw HTML into Om DOM code in CLJS. Simply paste in valid HTML and press the button.
  Then copy/paste the result into an editor and reformat. The converter will convert space text nodes into literal
  quoted spaces, but other than that is does a pretty effective job. If there are React attributes that get mis-translated
  then edit this file and add a mapping to the `attr-renames` map."
  (fulcro-app HTMLConverterApp))
