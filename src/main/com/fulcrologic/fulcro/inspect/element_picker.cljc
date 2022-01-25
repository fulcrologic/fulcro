(ns ^:no-doc com.fulcrologic.fulcro.inspect.element-picker
  (:require
    [clojure.string :as str]
    #?@(:cljs [[goog.object :as gobj]
               [goog.dom :as gdom]
               [goog.style :as gstyle]
               ["react-dom" :as react.dom]])
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.react.error-boundaries :as eb]))

(def base-me-style #js {:position       "absolute"
                        :display        "block"
                        :background     "rgba(67, 132, 208, 0.5)"
                        :pointer-events "none"
                        :overflow       "hidden"
                        :color          "#fff"
                        :padding        "3px 5px"
                        :box-sizing     "border-box"
                        :font-family    "monospace"
                        :font-size      "12px"
                        :z-index        "999999"})

(def base-ml-style #js {:position       "absolute"
                        :display        "block"
                        :pointer-events "none"
                        :box-sizing     "border-box"
                        :font-family    "sans-serif"
                        :font-size      "10px"
                        :z-index        "999999"
                        :background     "#333740"
                        :border-radius  "3px"
                        :padding        "6px 8px"
                        :color          "#ffab66"
                        :font-weight    "bold"
                        :white-space    "nowrap"})

(defn me-style [style]
  #?(:cljs
     (let [result (js-obj)]
       (gobj/extend result base-me-style style)
       result)))

(defn ml-style [style]
  #?(:cljs
     (let [result (js-obj)]
       (gobj/extend result base-ml-style style)
       result)))

(defn marker-element []
  (let [id "__fulcro_inspect_marker"]
    #?(:cljs
       (or (js/document.getElementById id)
         (doto (js/document.createElement "div")
           (gobj/set "id" id)
           (->> (gdom/appendChild js/document.body)))))))

(defn marker-label-element []
  (let [id "__fulcro_inspect_marker_label"]
    #?(:cljs
       (or (js/document.getElementById id)
         (doto (js/document.createElement "div")
           (gobj/set "id" id)
           (->> (gdom/appendChild js/document.body)))))))

(defn react-raw-instance [node]
  #?(:cljs
     (if-let [instance-key (->> (gobj/getKeys node)
                                (filter #(or (str/starts-with? % "__reactInternalInstance$")
                                             (str/starts-with? % "__reactFiber$")))
                                (first))]
       (gobj/get node instance-key))))

(defn react-instance [node]
  #?(:cljs
     (if-let [raw (react-raw-instance node)]
       (let [instance (or (gobj/getValueByKeys raw "_currentElement" "_owner" "_instance") ; react < 16
                       (gobj/getValueByKeys raw "return" "stateNode") ; react >= 16
                       )]
         ;; (React >= 16): If component body is wrapped in eb/error-boundary then we need to reach 2 levels deeper,
         ;; through eb/BodyContainer and eb/ErrorBoundary to get at the actual component
         (if (and (nil? instance) (= (gobj/getValueByKeys raw "return" "type") eb/BodyContainer))
           (gobj/getValueByKeys raw "return" "return" "return" "stateNode")
           instance)))))

(defn pick-element [{:keys [on-pick]
                     :or   {on-pick identity}}]
  #?(:cljs
     (let [marker       (marker-element)
           marker-label (marker-label-element)
           current      (atom nil)
           over-handler (fn [e]
                          (let [target (.-target e)]
                            (loop [target target]
                              (if target
                                (let [instance (some-> target react-instance)]
                                  (if (comp/component-instance? instance)
                                    (do
                                      (.stopPropagation e)
                                      (reset! current instance)
                                      (gdom/setTextContent marker-label (comp/component-name instance))

                                      (let [target' (react.dom/findDOMNode instance)
                                            offset  (gstyle/getPageOffset target')
                                            size    (gstyle/getSize target')]
                                        (gstyle/setStyle marker-label
                                          (ml-style #js {:left (str (.-x offset) "px")
                                                         :top  (str (- (.-y offset) 36) "px")}))

                                        (gstyle/setStyle marker
                                          (me-style #js {:width  (str (.-width size) "px")
                                                         :height (str (.-height size) "px")
                                                         :left   (str (.-x offset) "px")
                                                         :top    (str (.-y offset) "px")}))))
                                    (recur (gdom/getParentElement target))))))))
           pick-handler (fn self []
                          (on-pick @current)

                          (gstyle/setStyle marker #js {:display "none"})
                          (gstyle/setStyle marker-label #js {:display "none"})

                          (js/removeEventListener "click" self)
                          (js/removeEventListener "mouseover" over-handler))]

       (gstyle/setStyle marker (me-style #js {:display "block"
                                              :top     "-100000px"
                                              :left    "-100000px"}))

       (gstyle/setStyle marker-label (ml-style #js {:display "block"
                                                    :top     "-100000px"
                                                    :left    "-100000px"}))

       (js/addEventListener "mouseover" over-handler)

       (js/setTimeout
         #(js/addEventListener "click" pick-handler)
         10))))


(defn inspect-component [comp]
  #?(:cljs
     (let [state-map (some-> (comp/any->app comp) :com.fulcrologic.fulcro.application/state-atom deref)]
       {:fulcro.inspect.ui.element/display-name (comp/component-name comp)
        :fulcro.inspect.ui.element/props        (comp/props comp)
        :fulcro.inspect.ui.element/ident        (try
                                                  (comp/get-ident comp)
                                                  (catch :default _ nil))
        :fulcro.inspect.ui.element/static-query (try
                                                  (some-> comp comp/react-type comp/get-query)
                                                  (catch :default _ nil))
        :fulcro.inspect.ui.element/query        (try
                                                  (some-> comp comp/react-type (comp/get-query state-map))
                                                  (catch :default _ nil))})))

(defn install!
  "Install element picker support."
  []
  (log/info "Installing Inspect Element Picker")
  (reset! inspect/run-picker
    (fn [data]
      (let [{:fulcro.inspect.core/keys [app-uuid]} data]
        (pick-element
          {:fulcro.inspect.core/app-uuid
           app-uuid
           :on-pick
           (fn [comp]
             (if comp
               (let [details (inspect-component comp)]
                 (inspect/transact-inspector! [:fulcro.inspect.ui.element/panel-id
                                               [:fulcro.inspect.core/app-uuid app-uuid]]
                   [`(fulcro.inspect.ui.element/set-element ~details)]))
               (inspect/transact-inspector! [:fulcro.inspect.ui.element/panel-id
                                             [:fulcro.inspect.core/app-uuid app-uuid]]
                 [`(fulcro.client.mutations/set-props {:ui/picking? false})])))}))))
  true)