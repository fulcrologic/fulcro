(ns fulcro.client.dom-server-spec
  (:require
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.dom-server :as dom :refer [div p span render-to-str]]))

(defsc Sample [this props] (dom/div "Hello"))
(def ui-sample (prim/factory Sample))

(specification "Server-side Rendering" :focused
  (assertions
    "Simple tag rendering"
    (render-to-str (div {} "Hello"))
    => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-880209586\">Hello</div>"
    "Rendering with missing props"
    (render-to-str (div "Hello"))
    => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-880209586\">Hello</div>"
    "Rendering with kw props"
    (render-to-str (div :.a#1 "Hello"))
    => "<div class=\"a\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-244181499\">Hello</div>"
    "Rendering with kw and props map"
    (render-to-str (div :.a#1 {:className "b"} "Hello"))
    => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"385685127\">Hello</div>"
    (render-to-str (div :.a#1 {:className "b" :classes ["x" :.c]} "Hello"))
    => "<div class=\"a b x c\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1781864354\">Hello</div>"
    "Nested rendering"
    (render-to-str (div :.a#1 {:className "b"}
                     (p "P")
                     (p :.x (span "PS2"))))
    => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1768960473\"><p data-reactid=\"2\">P</p><p class=\"x\" data-reactid=\"3\"><span data-reactid=\"4\">PS2</span></p></div>"
    "Component child in DOM with props"
    (render-to-str (dom/div {:className "test"} (ui-sample {})))
    => "<div class=\"test\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-1251926300\"><div data-reactid=\"2\">Hello</div></div>"
    "Component child in DOM with kw shortcut"
    (render-to-str (dom/div :.TEST (ui-sample {})))
    => "<div class=\"TEST\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-1910432156\"><div data-reactid=\"2\">Hello</div></div>"
    "works with threading macro"
    (render-to-str (->>
                     (span "PS2")
                     (p :.x)
                     (div :.a#1 {:className "b"})))
    => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-213767666\"><p class=\"x\" data-reactid=\"2\"><span data-reactid=\"3\">PS2</span></p></div>"))

(specification "DOM elements are usable as functions" :focused
  (provided "The correct SSR function is called."
    (dom/element opts) => (assertions
                            (:tag opts) => 'div)

    (apply div {} ["Hello"])))
