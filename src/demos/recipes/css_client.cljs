(ns recipes.css-client
  (:require
    [untangled.client.data-fetch :as df]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    [untangled.client.core :as uc :refer [InitialAppState initial-state]]
    [untangled.client.css :as css :refer [css-merge local-class local-kw] :refer-macros [localize-classnames]]
    [garden.core :as g]
    [garden.units :refer [px]]
    [garden.stylesheet :as gs]))

(declare Root)

(def color 'black)

(defrecord MyCss []
  css/CSS
  (css [this] [[(local-kw MyCss :a) {:color 'blue}]]))

(defui ^:once Child
  static css/CSS
  (css [this]
    (let [p (local-kw Child :p)]
      (css-merge
        [p {:font-weight 'bold}]
        [(gs/at-media {:min-width (px 700)} [p {:color 'red}])])))
  static InitialAppState
  (initial-state [cls params] {:id 0 :label (:label params)})
  static om/IQuery
  (query [this] [:id :label])
  static om/Ident
  (ident [this props] [:child/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [id label]} (om/props this)]
      ; apply-css is a macro that looks for :class in maps and convers a single (or vector of) keywords
      ; to localized class names and rewrites it as :className. Using $ keeps it from localizing a name.
      (localize-classnames Child
        (dom/p #js {:class [:p :$r]} label)))))

(def ui-child (om/factory Child))

(defui ^:once Root
  static css/CSS
  (css [this] (css-merge
                MyCss
                Child))
  static InitialAppState
  (initial-state [cls params]
    {:child (initial-state Child {:label "Constructed Label"})})
  static om/IQuery
  (query [this] [:ui/react-key {:child (om/get-query Child)}])
  Object
  (render [this]
    (let [{:keys [child ui/react-key]} (om/props this)]
      (dom/div #js {:key react-key}
        ; YOU CAN EMBED THE STYLE RIGHT HERE (See also user.cljs)
        (dom/style nil (g/css (css/css Root)))
        (ui-child child)))))

