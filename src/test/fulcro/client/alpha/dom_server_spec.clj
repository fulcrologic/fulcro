(ns fulcro.client.alpha.dom-server-spec
  (:require
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [fulcro.client.alpha.dom-server :as dom :refer [div p span render-to-str]]))

(specification "Server-side Rendering"
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
    "Nested rendering"
    (render-to-str (div :.a#1 {:className "b"}
                     (p "P")
                     (p :.x (span "PS2"))))
    => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1768960473\"><p data-reactid=\"2\">P</p><p class=\"x\" data-reactid=\"3\"><span data-reactid=\"4\">PS2</span></p></div>"
    "works with threading macro"
    (render-to-str (->>
                     (span "PS2")
                     (p :.x)
                     (div :.a#1 {:className "b"})))
    => "<div class=\"a b\" id=\"1\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-213767666\"><p class=\"x\" data-reactid=\"2\"><span data-reactid=\"3\">PS2</span></p></div>"))

(specification "DOM elements are usable as functions"
  (provided "The correct SSR function is called."
    (dom/element opts) => (assertions
                            (:tag opts) => 'div)

    (apply div {} ["Hello"])))
