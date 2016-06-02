(ns untangled.client.ui-spec
  (:require [untangled-spec.core :refer
             [specification behavior assertions when-mocking]]
            [untangled.client.ui :as uc-ui]))

(specification "defui from unganled.client.ui"
  (when-mocking
    (uc-ui/process-meta-info _) => {:file "some-file"
                                    :line 13
                                    :column 42}
    (assertions "defui works with the normal om.next syntax"
      (macroexpand '(untangled.client.ui/defui RootComp {}
                      static om/Ident
                      (ident [this _] [:thing/by-id 3])
                      static om/IQuery
                      (query [this] [:query])
                      Object
                      (a-method [this] :foo)
                      (render [this] (dom/div this "root"))))
      => '(do (om.next/defui RootComp
                static om/Ident
                (ident [this _] [:thing/by-id 3])
                static om/IQuery
                (query [this] [:query])
                Object
                (a-method [this] :foo)
                (render [this] (untangled.client.ui/wrap-render
                                 {:line 13
                                  :column 42
                                  :file "some-file"}
                                 (dom/div this "root"))))
              (def ui-root-comp (om.next/factory RootComp {}))))))
