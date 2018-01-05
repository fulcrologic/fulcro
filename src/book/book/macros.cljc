(ns book.macros
  #?(:cljs (:require-macros book.macros))
  (:require
    [fulcro.client :as fc]
    #?(:cljs [devcards.util.edn-renderer :as edn])
    #?(:cljs [goog.object :as obj])
    fulcro-css.css
    [fulcro.client.dom :as dom]
    [fulcro.client.logging :as log]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.primitives :as prim :refer [defsc]]))

#?(:clj (def clj->js identity))

(defmutation update-db-view [{:keys [value]}]
  (action [{:keys [state]}]
    (swap! state assoc :watched-state value))
  (refresh [env] [:watched-state]))

#?(:cljs
   (defn watch-state [this example-app]
     (let [target-app-state (some-> example-app :reconciler prim/app-state)]
       (when target-app-state
         (prim/transact! this `[(update-db-view {:value ~(deref target-app-state)})])
         (add-watch target-app-state
           :example-watch (fn []
                            (prim/transact! this `[(update-db-view {:value ~(deref target-app-state)})])))))))

(defsc AppHolder [this _ _ {:keys [app-holder]}]
  {:shouldComponentUpdate (fn [_ _] false)
   :css                   [[:.app-holder {:border  "2px solid grey"
                                          :padding "10px"}]]
   :componentDidMount     (fn []
                            #?(:cljs (let [{:keys [app root]} (meta (prim/props this))]
                                       (if (and app root)
                                         (if-let [target-div (obj/get this "appdiv")]
                                           (let [app (fc/mount app root target-div)]
                                             (watch-state this app))
                                           (js/console.log "App holder: Target div not found."))
                                         (js/console.log "App holder: Not given an app or root" :app app :root root)))))}
  #?(:clj  (dom/div nil "")
     :cljs (dom/div #js {:className app-holder :ref (fn [r] (obj/set this "appdiv" r))} "")))

(def ui-app-holder (prim/factory AppHolder))

(defsc EDN [this {:keys [ui/open?] :as props} {:keys [edn]} {:keys [toggle-button db-block]}]
  {:initial-state {:ui/open? false}
   :query         [:ui/open?]
   :css           [[:.db-block {:padding "5px"}]
                   [:.toggle-button {:font-size "8pt"
                                     :margin    "5px"}]]
   :ident         (fn [] [:widgets/by-id :edn-renderer])}
  #?(:cljs
     (dom/div #js {:className "example-edn"}
       (dom/button #js {:className toggle-button :onClick (fn [] (m/toggle! this :ui/open?))} "Toggle DB View")
       (dom/div #js {:className db-block :style #js {:display (if open? "block" "none")}}
         (edn/html-edn edn)))))

(def ui-edn (prim/factory EDN))

(defsc ExampleRoot [this {:keys [edn-tool watched-state title example-app] :as props} _ {:keys [example-title]}]
  {:query         [{:edn-tool (prim/get-query EDN)}
                   :watched-state
                   :title
                   :example-app]
   :css           [[:.example-title {:margin                  "0"
                                     :padding                 "5px"
                                     :border-top-left-radius  "8px"
                                     :border-top-right-radius "8px"
                                     :width                   "100%"
                                     :color                   "white"
                                     :background-color        "rgb(70, 148, 70)"}]]
   :css-include   [EDN AppHolder]
   :initial-state {:edn-tool {}}}
  (let [has-title? (not= "" title)]
    (dom/div nil
      (when has-title? (dom/h4 #js {:className example-title} title))
      (ui-app-holder example-app)
      (when has-title? (ui-edn (prim/computed edn-tool {:edn watched-state}))))))

(defn new-example [{:keys [title example-app root-class]}]
  (fc/new-fulcro-client
    :initial-state (merge (prim/get-initial-state ExampleRoot {})
                     {:example-app (with-meta {} {:app example-app :root root-class})
                      :title       title})))

(defmacro defexample [title root-class id & args]
  (let [app         (symbol (str "fulcroapp-" id))
        example-app (symbol (str "example-container-" id))]
    `(do
       (defonce ~app (fulcro.client/new-fulcro-client :reconciler-options {:id ~(name app)} ~@args))
       (defonce ~example-app (book.macros/new-example {:title ~title :example-app ~app :root-class ~root-class}))
       (fulcro.client/mount ~example-app ExampleRoot ~id))))

(defmacro deftool [root-class id & args]
  (let [app (symbol (str "fulcroapp-" id))]
    `(do
       (defonce ~app (fulcro.client/new-fulcro-client ~@args))
       (fulcro.client/mount ~app ~root-class ~id))))
