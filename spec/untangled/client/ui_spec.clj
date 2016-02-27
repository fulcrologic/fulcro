(ns untangled.client.ui-spec
  (:require [untangled-spec.core :refer
             [specification behavior assertions]]

            [untangled.client.ui :as uc-ui]))

(specification "untangled client's defui works the same as defui"
  (assertions
    (macroexpand '(untangled.client.ui/defui RootComp {}
                    static om/IQuery
                    (query [this] [:query])
                    static om/Ident
                    (ident [this _] [:thing/by-id 3])
                    Object
                    (render [this] this)))
    => '(do (om.next/defui RootComp
              static om/IQuery
              (query [this] [:query])
              static om/Ident
              (ident [this _] [:thing/by-id 3])
              Object
              (render [this] this))
            (def ui-root-comp (om.next/factory RootComp {})))))
