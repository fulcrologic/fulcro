(ns fulcro.client.fancy-defui
  #?(:cljs
     (:require-macros
       [fulcro.client.augmentation :as aug ]))
  (:require
    #?(:clj [fulcro.client.augmentation :as aug :refer [inject-augment]])
    #?(:cljs [devcards.core :as dc :include-macros true])
            [fulcro.client.core :as fc]
            [om.next :as om]
            [om.dom :as dom]))

; An augment for adding @Class support to use it as the factory
#?(:clj
   (defmethod aug/defui-augmentation ::DerefFactory
     [{:keys [defui/ui-name env/cljs?]} ast args]
     (inject-augment ast 'static
       (if cljs? 'IDeref 'clojure.lang.IDeref)
       (if cljs? '-deref 'deref)
       `(fn [_#] (om.next/factory ~ui-name ~(or args {}))))))

#?(:clj
   (defmethod aug/defui-augmentation ::WithExclamation
     [_ ast {:keys [excl]}]
     (aug/wrap-augment ast 'Object 'render
       (fn [_ body]
         `(om.dom/div nil
            (om.dom/p nil ~(str excl))
            ~body)))))

#?(:clj
   (aug/add-defui-augmentation-group ::BuiltIns
     (fn [_augment]
       '[::DerefFactory
         (::WithExclamation {:excl "BuiltIns Engaged!"})])))

(aug/defui ListItem [(::DerefFactory {:keyfn :value})]
  Object
  (render [this]
    (dom/li nil
      (:value (om/props this)))))

(aug/defui ThingB [(::BuiltIns {::WithExclamation {:excl "THING B OVERRIDE!"}})]
  Object
  (render [this]
    (dom/div nil
      (dom/ul nil
        (map @ListItem (map hash-map (repeat :value) (range 6)))))))

(aug/defui ThingA
  {:prod [(::WithExclamation {:excl "IN PROD MODE"})]
   :dev  [(::WithExclamation {:excl "IN DEV MODE"})]}
  Object
  (render [this]
    (let [{:keys [ui/react-key]} (om/props this)]
      (dom/div #js {:key react-key}
        "Hello World!"
        (@ThingB)))))

#?(:cljs
   (defonce client (atom (fc/new-fulcro-test-client))))

#?(:cljs
   (dc/defcard fancy-defui
     "##fulcro.client.ui/defui"
     (dc/dom-node
       (fn [_ node]
         (reset! client (fc/mount @client ThingA node))))))
