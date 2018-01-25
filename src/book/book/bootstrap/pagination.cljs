(ns book.bootstrap.pagination
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.bootstrap.helpers :as helper :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]
            [fulcro.ui.html-entities :as ent]))

(defsc pagination [t p]
  (dom/div nil
    (dom/h4 nil "Pager")
    (render-example "100%" "95px"
      (b/pager {}
        (b/pager-previous {:onClick #(js/alert "Back")}
          (str ent/laquo " Back"))
        (b/pager-next {:onClick #(js/alert "Next")}
          (str "Next " ent/raquo))))
    (dom/h4 nil "Pagination Bar")
    (render-example "100%" "400px"
      (b/table {:styles #{:condensed}}
        (dom/tbody nil
          (dom/tr nil
            (dom/th nil "Pages (none active)")
            (dom/td nil (b/pagination {}
                          (b/pagination-entry {:label ent/laquo :onClick #(js/alert "Back")})
                          (b/pagination-entry {:label "1" :onClick #(js/alert "1")})
                          (b/pagination-entry {:label "2" :onClick #(js/alert "2")})
                          (b/pagination-entry {:label "3" :onClick #(js/alert "3")})
                          (b/pagination-entry {:label ent/raquo :onClick #(js/alert "Next")}))))
          (dom/tr nil
            (dom/th nil "With one active, and next disabled")
            (dom/td nil (b/pagination {}
                          (b/pagination-entry {:label ent/laquo :onClick #(js/alert "Back")})
                          (b/pagination-entry {:label "1" :active true :onClick #(js/alert "1")})
                          (b/pagination-entry {:label "2" :onClick #(js/alert "2")})
                          (b/pagination-entry {:label "3" :onClick #(js/alert "3")})
                          (b/pagination-entry {:label ent/raquo :disabled true :onClick #(js/alert "Next")}))))
          (dom/tr nil
            (dom/th nil "Small")
            (dom/td nil (b/pagination {:size :sm}
                          (b/pagination-entry {:label ent/laquo :onClick #(js/alert "Back")})
                          (b/pagination-entry {:label "1" :onClick #(js/alert "1")})
                          (b/pagination-entry {:label "2" :onClick #(js/alert "2")})
                          (b/pagination-entry {:label "3" :onClick #(js/alert "3")})
                          (b/pagination-entry {:label ent/raquo :onClick #(js/alert "Next")}))))
          (dom/tr nil
            (dom/th nil "Large")
            (dom/td nil (b/pagination {:size :lg}
                          (b/pagination-entry {:label ent/laquo :onClick #(js/alert "Back")})
                          (b/pagination-entry {:label "1" :onClick #(js/alert "1")})
                          (b/pagination-entry {:label "2" :onClick #(js/alert "2")})
                          (b/pagination-entry {:label "3" :onClick #(js/alert "3")})
                          (b/pagination-entry {:label ent/raquo :onClick #(js/alert "Next")})))))))))
