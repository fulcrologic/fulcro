(ns fulcro.server-render-spec
  (:require [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.client.dom :as dom]
            [fulcro-spec.core :refer [specification behavior assertions]]
            [fulcro.server-render :as ssr]
            [fulcro.client.util :as util]
            [fulcro.client.core :as fc]))

(defui Item
  static prim/InitialAppState
  (initial-state [cls {:keys [id label]}] {:id id :label label})
  static prim/IQuery
  (query [this] [:id :label])
  static prim/Ident
  (ident [this props] [:items/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [label]} (prim/props this)]
      (dom/div #js {:className "item"}
        (dom/span #js {:className "label"} label)))))

(def ui-item (prim/factory Item {:keyfn :id}))

(defui Root
  static prim/InitialAppState
  (initial-state [cls params] {:items [(prim/get-initial-state Item {:id 1 :label "A"})
                                       (prim/get-initial-state Item {:id 2 :label "B"})]})
  static prim/IQuery
  (query [this] [{:items (prim/get-query Item)}])
  Object
  (render [this]
    (let [{:keys [items]} (prim/props this)]
      (dom/div #js {:className "root"}
        (mapv ui-item items)))))

(def ui-root (prim/factory Root))

#?(:clj
   (specification "Server-side rendering"
     (assertions
       "Can generate a string from UI with initial state"
       (dom/render-to-str (ui-root (prim/get-initial-state Root {}))) => "<div class=\"root\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"830295248\"><div class=\"item\" data-reactid=\"2\"><span class=\"label\" data-reactid=\"3\">A</span></div><div class=\"item\" data-reactid=\"4\"><span class=\"label\" data-reactid=\"5\">B</span></div></div>")))

#?(:clj
   (specification "SSR script tag generation"
     (assertions
       "puts an assignment on the document window"
       (ssr/initial-state->script-tag []) => "<script type='text/javascript'>\nwindow.INITIAL_APP_STATE = '[]'\n</script>\n")))

#?(:cljs
   (specification "SSR initial-state extraction"
     (let [state (util/transit-clj->str [])]
       (set! (.-INITIAL_APP_STATE js/window) state)
       (assertions
         "Can pull the initial state from the document's window"
         (ssr/get-SSR-initial-state) => []))))

(def table-1 {:type :table :id 1 :rows [1 2 3]})
(defui Table
  static prim/InitialAppState
  (initial-state [c p] table-1)
  static prim/IQuery
  (query [this] [:type :id :rows]))

(def graph-1 {:type :graph :id 1 :data [1 2 3]})
(defui Graph
  static prim/InitialAppState
  (initial-state [c p] graph-1)
  static prim/IQuery
  (query [this] [:type :id :data]))

(defui Reports
  static prim/InitialAppState
  (initial-state [c p] (prim/get-initial-state Graph nil))    ; initial state will already include Graph
  static prim/Ident
  (ident [this props] [(:type props) (:id props)])
  static prim/IQuery
  (query [this] {:graph (prim/get-query Graph) :table (prim/get-query Table)}))

(defui MRRoot
  static prim/InitialAppState
  (initial-state [c p] {:reports (prim/get-initial-state Reports nil)})
  static prim/IQuery
  (query [this] [{:reports (prim/get-query Reports)}]))

(specification "Build Initial State"
  (let [state-tree (prim/get-initial-state MRRoot nil)
        norm-db    (ssr/build-initial-state state-tree MRRoot)]
    (assertions
      "Builds a normalized database from the given state tree"
      (keys (get norm-db :graph)) => [1]
      (get norm-db :reports) => [:graph 1]
      "Merges in the non-initial elements of unions"
      (keys (get norm-db :table)) => [1]
      (get-in norm-db [:table 1]) => {:type :table :id 1 :rows [1 2 3]})))
