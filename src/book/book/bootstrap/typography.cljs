(ns book.bootstrap.typography
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc Typography [t p]
  (render-example "100%" "1500px"
    (b/container {}
      (b/row nil
        (b/col {:xs 6} "(b/lead {} \"Lead Text\")")
        (b/col {:xs 6} (b/lead {} "Lead Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/p #js {} \"Paragraph Text\")")
        (b/col {:xs 6} (dom/p #js {} "Paragraph Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/mark #js {} \"Highlighted Text\")")
        (b/col {:xs 6} (dom/mark #js {} "Highlighted Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/del #js {} \"Deleted Text\")")
        (b/col {:xs 6} (dom/del #js {} "Deleted Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/s #js {} \"Strikethrough Text\")")
        (b/col {:xs 6} (dom/s #js {} "Strikethrough Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/ins #js {} \"Inserted Text\")")
        (b/col {:xs 6} (dom/ins #js {} "Inserted Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/u #js {} \"Underlined Text\")")
        (b/col {:xs 6} (dom/u #js {} "Underlined Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/small #js {} \"Small Text\")")
        (b/col {:xs 6} (dom/small #js {} "Small Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/strong #js {} \"Bold Text\")")
        (b/col {:xs 6} (dom/strong #js {} "Bold Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/em #js {} \"Italic Text\")")
        (b/col {:xs 6} (dom/em #js {} "Italic Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/address \"Some Company\" :street \"111 NW 1st St.\" :city-state \"Chicago, IL 43156\" :phone \"(316) 555-1212\")")
        (b/col {:xs 6} (b/address "Some Company" :street "111 NW 1st St." :city-state "Chicago, IL 43156"
                         :phone "(316) 555-1212")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/blockquote nil (dom/p nil \"Block quoted Text\"))")
        (b/col {:xs 6} (dom/blockquote nil (dom/p nil "Block quoted Text"))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/quotation {:source \"in Joe's Diary\" :credit \"Joe\"} (dom/p nil \"Some crap he said.\"))")
        (b/col {:xs 6} (b/quotation {:source "in Joe's Diary" :credit "Joe"} (dom/p nil "Some crap he said."))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/quotation {:align :right :source \"in Joe's Diary\" :credit \"Joe\"} (dom/p nil \"Some crap he said.\"))")
        (b/col {:xs 6} (b/quotation {:align :right :source "in Joe's Diary" :credit "Joe"} (dom/p nil "Some crap he said."))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/ul #js {} (dom/li nil \"A\") (dom/li nil \"B\")")
        (b/col {:xs 6} (dom/ul #js {} (dom/li nil "A") (dom/li nil "B"))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/plain-ul {} (dom/li nil \"A\") (dom/li nil \"B\")")
        (b/col {:xs 6} (b/plain-ul {} (dom/li nil "A") (dom/li nil "B"))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/inline-ul {} (dom/li nil \"A\") (dom/li nil \"B\")")
        (b/col {:xs 6} (b/inline-ul {} (dom/li nil "A") (dom/li nil "B")))))))


