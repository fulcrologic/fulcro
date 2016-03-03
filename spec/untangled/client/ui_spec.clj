(ns untangled.client.ui-spec
  (:require [untangled-spec.core :refer
             [specification behavior assertions when-mocking]]

            [untangled.client.ui :as uc-ui]))

(specification "defui from unganled.client.ui"
  (when-mocking
    (uc-ui/process-meta-info _) => {:file "some-file"
                                    :line 13
                                    :column 42}
    (assertions "defui+ works with kw pairs"
      (uc-ui/transform+ {:query '(fn [x] x)
                         :render '(fn [y] y)})
      => '(static om/IQuery (query [x] x) Object (render [y] y))
      (macroexpand '(untangled.client.ui/defui+ RootComp
                      :opts {}
                      :query (fn [this] [:query])
                      :ident (fn [this _] [:thing/by-id 3])
                      :render (fn [this] (dom/div this "root"))
                      :a-method (fn [this] :foo)))
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
              (def ui-root-comp (om.next/factory RootComp {})))

      "defui works with the normal om.next syntax"
      (uc-ui/transform :meta-info
                       '(static om/Ident
                                (ident [this _] [:thing/by-id 3])
                                static om/IQuery
                                (query [this] [:query])
                                Object
                                (a-method [this] :foo)
                                (render [this] (dom/div this "root"))))
      => '(static om/Ident
                  (ident [this _] [:thing/by-id 3])
                  static om/IQuery
                  (query [this] [:query])
                  Object
                  (a-method [this] :foo)
                  (render [this] (untangled.client.ui/wrap-render
                                   :meta-info (dom/div this "root"))))
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
