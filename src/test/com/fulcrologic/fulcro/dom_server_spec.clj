(ns com.fulcrologic.fulcro.dom-server-spec
  (:require
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom-server :as dom :refer [div p span render-to-str]]))

(defsc Sample [this props] (dom/div "Hello"))
(def ui-sample (comp/factory Sample))

(defsc SampleFragment [this props]
  (comp/fragment (dom/p "a") (dom/p "b")))
(def ui-sample-fragment (comp/factory SampleFragment))

(specification "Server-side Rendering" :focus
  (assertions
    "Simple tag rendering"
    (render-to-str (div {} "Hello"))
    => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-880209586\">Hello</div>"
    "strings adjacent to elements"
    (render-to-str (div {} "hello" (div)))
    => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1251944041\"><!-- react-text: 2 -->hello<!-- /react-text --><div data-reactid=\"3\"></div></div>"
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

(specification "DOM elements are usable as functions"
  (provided "The correct SSR function is called."
    (dom/element opts) => (assertions
                            (:tag opts) => 'div)

    (apply div {} ["Hello"])))

(specification "Fragments"
  (assertions
    "Allow multiple elements to be combined into a parent"
    (dom/render-to-str (dom/div (comp/fragment {:key 1} (dom/p "a") (dom/p "b"))))
    =>
    "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"271326992\"><p data-reactid=\"2\">a</p><p data-reactid=\"3\">b</p></div>"
    (dom/render-to-str (dom/div (ui-sample-fragment {})))
    =>
    "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"271326992\"><p data-reactid=\"2\">a</p><p data-reactid=\"3\">b</p></div>"

    "Props are optional"
    (dom/render-to-str (dom/div (comp/fragment (dom/p "a") (dom/p "b"))))
    =>
    "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"271326992\"><p data-reactid=\"2\">a</p><p data-reactid=\"3\">b</p></div>"))

(defsc Child [this props]
  (apply dom/div {}
    (comp/children this)))

(def ui-child (comp/factory Child))

(defsc Root [this props]
  (ui-child {}
    (ui-sample {})
    (ui-sample {})))

(def ui-root (comp/factory Root))

(specification "Children support"
  (assertions
    "Allow multiple children to be passed to a component"
    (dom/render-to-str (ui-root {}))
    =>
    "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"1582179713\"><div data-reactid=\"2\">Hello</div><div data-reactid=\"3\">Hello</div></div>"))

(defsc LCC [this props]
  {:initLocalState (fn [this] {:x 2})}
  (let [x (comp/get-state this :x)]
    (dom/div x)))

(def ui-lcc (comp/factory LCC))

(specification "Component-local state support"
  (assertions
    "Renders the correct result when local state is used"
    (dom/render-to-str (ui-lcc {}))
    =>
    "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-2047864948\">2</div>"))

(defsc ComponentWithFragment [this props]
  {}
  (comp/fragment
    (dom/div "A")
    (dom/div "B")))

(def ui-component-with-fragment (comp/factory ComponentWithFragment))

(defsc ComponentWithVector [this props]
  {}
  [(dom/div "A")
   (dom/div "B")])

(def ui-component-with-vector (comp/factory ComponentWithVector))

(specification "React Fragments"
  (assertions
    "Renders the raised elements"
    (dom/render-to-str (ui-component-with-fragment {})) => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-366207178\">A</div><div data-reactroot=\"\" data-reactid=\"1\">B</div>"
    "Allows fragments as vectors"
    (dom/render-to-str (ui-component-with-vector {})) => "<div data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"-366207178\">A</div><div data-reactroot=\"\" data-reactid=\"1\">B</div>"))
