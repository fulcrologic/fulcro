(ns com.fulcrologic.fulcro.main
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    ["react" :as react :refer [useState useEffect]]
    ["react-dom" :as react-dom]
    [fulcro.client.dom :as dom]
    [fulcro.util :as util]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.transactions :as txn]))

(def ^:dynamic *reconciler* nil)

(defn render! [c]
  (.render react-dom c (.getElementById js/document "app"))
  :done)

(comment
  (defsc ListItem [this {:item/keys [label]} {:keys [x]}]
    {:query                    [:item/id :item/label]
     :ident                    [:item/id :item/id]
     :getDerivedStateFromError (fn [err])
     :initial-state            {:item/id (random-uuid) :item/label :param/label}}
    (dom/li
      label (str "." x)
      (dom/button {:onClick #(m/set-value! this :item/label "B")} "+")))

  (def ui-list-item (prim/factory ListItem))

  (defsc TodoList [this {:list/keys [label items]}]
    {:query         [:list/id :list/label {:list/items (prim/get-query ListItem)}]
     :ident         [:list/id :list/id]
     :initial-state (fn [_]
                      {:list/id    1
                       :list/label "My List"
                       :list/items (mapv
                                     #(prim/get-initial-state ListItem {:label (str "a" %)})
                                     (range 1 1000))})}
    (let [y (prim/get-state this :y)]
      (dom/div
        (dom/h4 label)
        (dom/ul
          (dom/button {:onClick #(prim/set-state! this {:y 22})} "Regress!")
          (map (fn [item]
                 (ui-list-item (prim/computed item {:x (or y 1)}))) items)))))

  (def ui-list (prim/factory TodoList))

  (defsc Root [this {:keys [the-list]}]
    {:query         [{:the-list (prim/get-query TodoList)}]
     :initial-state {:the-list {}}}
    (dom/table
      (dom/tbody
        (dom/tr
          (dom/td
            (ui-list the-list))
          (dom/td
            (ui-list the-list))))))

  (prim/defsc C [this props]
    (let [a (prim/get-state this :a)]
      (dom/div "Hi " a)))

  (defonce app-db (atom {:counter/id {1 {:counter/id 1 :counter/n 1}
                                      2 {:counter/id 2 :counter/n 10}}}))
  (defonce index (atom {}))

  (defn get-query [c] (.-fulcro_query c))
  (defn get-props [this] (.-fulcro_props this))
  (defn get-ident
    ([this] ((.-fulcro_ident this) (get-props this)))
    ([c props] ((.-fulcro_ident c) props)))

  (defn index! [ident setProps]
    (swap! index update ident (fnil conj #{}) setProps))

  (defn drop! [ident setProps]
    (swap! index update ident disj setProps))

  (defn use-state [default-value]
    (let [result (useState default-value)]
      [(aget result 0) (aget result 1)]))

  (defn use-effect
    ([f] (useEffect f))
    ([f deps] (useEffect f (clj->js deps))))

  (defn use-fulcro
    "React Hook to simulate hooking fulcro state database up to a hook-based react component."
    [component query ident-fn initial-ident]
    (let [[props setProps] (use-state {})]                  ; this is how the component gets props, and how Fulcro would update them
      (use-effect                                           ; the empty array makes this a didMount effect
        (fn []
          (set! (.-fulcro_query component) query)           ;; record the query and ident function on the component function itself
          (set! (.-fulcro_ident component) ident-fn)
          ;; pull initial props from the database, and set them on the props
          (let [initial-props (prim/db->tree query (get-in @app-db initial-ident) @app-db)]
            (setProps initial-props))
          ;; Add the setProps function to the index so we can call it later (set of functions stored by ident)
          (index! initial-ident setProps)
          ;; cleanup function: drops the update fn from the index
          (fn []
            (drop! initial-ident setProps)))
        #js [])
      props)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SAMPLE 1: How a component could act as it's own data root (rendered as a child, but not tied to the parent's query)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This is kind of what defsc would generate...nested props take a little more work
(comment
  (defonce Counter
    ;; here the idea is that we're able to define a root without an incoming join edge. This lets any component act as
    ;; a data root, even though it isn't the app root
    (fn [config-props]
      (let [{:counter/keys [id n] :as props} (use-fulcro
                                               Counter      ; component
                                               [:counter/id :counter/n] ; query
                                               (fn [p] [:counter/id (:counter/id p)]) ; ident-fn
                                               [:counter/id (aget config-props "mount-id")])] ; initial-ident
        (dom/div
          (dom/div "Counter " id)
          (dom/button {:onClick (fn incr* []
                                  (let [;; MUTATE
                                        ident         [:counter/id (aget config-props "mount-id")]
                                        _             (swap! app-db update-in (conj ident :counter/n) inc)
                                        ;; REFRESH is trivial..denormalize and set props via index stored set props functions
                                        query         (get-query Counter)
                                        props         (prim/db->tree query (get-in @app-db ident) @app-db)
                                        set-props-fns (get @index ident)]
                                    ;; Refreshes all components with the given ID.
                                    (doseq [set-props set-props-fns]
                                      (set-props props))))}
            (str n))))))

  (defn ui-counter [props]
    (dom/create-element Counter props))

  )


(comment
  (render!
    (dom/div
      (ui-counter #js {:mount-id 1})
      (ui-counter #js {:mount-id 2})
      (ui-counter #js {:mount-id 1})))

  index)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SAMPLE 2: How a normal Fulcro component (joined to the parent) would work (props need a time marker to prevent
;; accidental time reversal on parent local-state updates)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

  (defn use-fulcro-nonroot
    "React Hook to simulate hooking fulcro state database up to a hook-based react component."
    [ident]
    (let [[props setProps] (use-state (with-meta {} {:time 0}))]
      (use-effect
        (fn []
          (log/info "Indexing ident " ident)
          (index! ident setProps)
          (fn []
            (log/info "Dropping index for ident " ident)
            (drop! ident setProps)))
        ;; refresh list. ident needs to be a string because react compares with ===
        #js [(str ident) setProps])
      props))

  (let [query    [:item/id :item/label]
        ident-fn (fn [p] [:item/id (:item/id p)])]
    (defonce TodoItem
      (fn [props]
        (let [props-from-parent (.-fp props)
              parent-time       (-> props-from-parent meta :time)
              ident             (get-ident TodoItem props-from-parent)
              _                 (log/info :props props-from-parent :ident ident)
              local-props       (use-fulcro-nonroot ident)
              local-time        (-> local-props meta :time)
              {:item/keys [label] :as real-props} (if (and local-time (pos-int? local-time)
                                                        (> local-time parent-time))
                                                    local-props
                                                    props-from-parent)]
          (dom/li label))))
    (set! (.-fulcro_query TodoItem) query)
    (set! (.-fulcro_ident TodoItem) ident-fn))

  (defn ui-todo-item [props]
    (dom/create-element TodoItem #js {:fp props}))

  (defn t [data tm] (with-meta data {:time tm})))

(comment

  ;; Props-based render
  ;; Setting time manually.  The UI targeted refresh (set-props-for-2) adds time to props, as does render from props
  ;; render logic prevents rendering going "back in time"
  (let [props-refresh-time 1]
    (render!
      (dom/ol
        (ui-todo-item (t {:item/id 1 :item/label "A"} props-refresh-time))
        (ui-todo-item (t {:item/id 2 :item/label "B"} props-refresh-time))
        (ui-todo-item (t {:item/id 3 :item/label "C"} props-refresh-time)))))

  ;; Local (targeted props) UI refresh
  ;; This will update the prior rendering the first time you use it (since time = 2, and props time was 1),
  ;; but then the props-based rendering won't refresh unless you bump time to 3+
  (let [local-refresh-time 2
        set-props-for-2    (first (get @index [:item/id 2]))]
    (set-props-for-2 (t {:item/id 2 :item/label "XXX"} local-refresh-time)))

  )

#_#_(defonce TodoList
      (fn [props]
        (let [props-from-parent (.-fp props)
              parent-time       (-> props-from-parent meta :time)
              [local-props setProps] (use-fulcro-nonroot TodoList [:list/id :list/name]
                                       (fn [p] [:list/id (:list/id p)])
                                       {})
              local-time        (-> local-props meta :time)]
          ;; this would need to be a time-based check as well in case the parent was updating for setState reasons,
          ;; and could really just choose most recent without needing to do an actual setProps
          (when (not= props-from-parent local-props)
            (setProps props-from-parent))

          )))

    (defn ui-todo-list [props]
      (dom/create-element TodoList #js {:fp props}))

;(defonce client (atom (fc/make-fulcro-client {})))
;(swap! client fc/mount Root "app")

(defsc Sample
  "Some documentation string"
  [this {:person/keys  [name id]
         :unicorn/keys [x]} computed {:keys [some-value]}]
  {:componentDidMount  (fn [this] (log/info "Mounted" (comp/component-options this)))
   :componentDidUpdate (fn [this pp ps] (log/info "Component Did Update" pp ps))
   :initLocalState     (fn [this]
                         (log/info "Getting initial local state" (comp/component-options this))
                         {:n 22})
   :css                [:.boo [:color "red"]]
   :form-fields        #{:person/name}
   :constructor        (fn [this props] (log/info "Built with props" this props))
   :query              [:person/id :person/name :unicorn/x]
   :ident              :person/id}
  (let [n (comp/get-state this :n)]
    (dom/div
      (dom/div "TODO:" (str "id:" id " name:" name " state n:" n " some: " some-value))
      (dom/button {:onClick (fn []
                              (comp/update-state! this update :n inc))} "Click me!"))))

(defn ui-sample [props & children]
  (let [{::app/keys [middleware]} *reconciler*
        ep-mw       (get middleware :extra-props-middleware)
        extra-props (when ep-mw
                      (this-as this (ep-mw this)))]
    (dom/create-element Sample #js {"fulcro$value"       props
                                    "fulcro$extra_props" extra-props
                                    "fulcro$reconciler"  *reconciler*})))

(defn wrap-my-extra
  ([] (fn [this] {:some-value 1}))
  ([handler]
   (fn [this]
     (merge (handler this) {:some-value 1}))))

(defn now [] (inst-ms (js/Date.)))

(defn wrap-time-render []
  (fn [this real-render]
    (let [start  (now)
          result (real-render)
          end    (now)]
      (log/info "Rendered " (.-displayName this) "in " (- end start))
      result)))

;; TASK: Support 2 kinds of middleware: prop/extraarg, and render wrapping
(def app (app/fulcro-app {:extra-props-middleware (wrap-my-extra)
                          :render-middleware      (wrap-time-render)}))

(comment
  (binding [*reconciler* app]
    (render!
      (dom/div
        (ui-sample {:person/id 21 :person/name "Tony" :y 49})))))
