---
layout: post
author: "Tony Kay"
title:  "Leveraging Shadow DOM"
date:   2017-10-26 16:16:01 -0600
categories: "shadow dom"
---

Recently I had a customer ask me about embedding Fulcro applications into an existing web software system
such that the styles and javascript won't conflict. Clojurescript, Fulcro, and React already
give us quite a bit of power on this front due to the following features:

- Closure has an `--output_wrapper` option. You can enable it using the `:output_wrapper` compiler option of
Clojurescript (when using advanced optimizations). This will eliminate javascript conflicts.
- Fulcro CSS allows you to namespace CSS written in Garden on your React components, allowing you
to safely isolate your component CSS from the CSS of the page.
- React allows an app to be mounted on any subportion of the DOM.

Unfortunately, this still may not be enough! The primary problem is that styles already on the page
can still bleed into your Fulcro application.

### Enter The Shadow

Many browsers now support shadow DOM. This is a feature where you can safely isolate a portion of the DOM
from the other content of the page. I decided to try some experiments to see how well this works.

The basics, as described in the standard, are that you attach a shadow root onto an existing DOM node,
and this shadowed DOM becomes the thing that is displayed instead. The web components standard
does a lot more with it from there, but for our purposes, this is enough! Isolation of
our React UI is possible.

The following simple component seems to do the trick:

{% highlight clojure linenos %}
(defui ShadowDOM
 Object
 (shouldComponentUpdate [this np ns] true)
 (componentDidMount [this]
   (let [dom-node (dom/node this)
         {:keys [open-boundary? delegates-focus?]
          :or   {open-boundary?   true
                 delegates-focus? false}} (om/props this)
         root     (when (exists? (.-attachShadow dom-node))
                    (.attachShadow dom-node
                      #js {:mode (if open-boundary? "open" "closed")
                           :delegatesFocus delegates-focus?}))]
     (om/react-set-state! this {:root root})))
 (componentDidUpdate [this pp ps]
   (let [children    (om/children this)
         shadow-root (om/get-state this :root)
         child       (first children)]
     (if (and shadow-root child)
       (dom/render child shadow-root))))
 (render [this]
   ; placeholder node to attach shadow DOM to
   (dom/div #js {:style #js {:font-size "14pt" :color "red"}}
     "Your browser does not support shadow DOM. Please try Chrome.")))
{% endhighlight %}

The basic operation just leverages some basics of React lifecycle.

Line 3 defines a `shouldComponentUpdate` that will always try to update this
component. This particular component won't have props that change, and
`defui`'s default is to not refresh if the props have not changed.

Line 4 is where the majority of the work happens. Once the primary div
(from render on line 20) is in the DOM, the `componentDidMount` will fire.
This is our signal that we can create a shadow DOM in the real DOM.

We do this by calling the standard `.attachShadow` on the dom node, which
we can find from `this` (the current component) using `dom/node`.

On line 13 we save off the shadow dom in component local state, since
we'll need that on every refresh.

Now, when the parent refreshes and tries to render this shadow dom
component, it will see no change from `render` (it will still be
the same primary div). Once rendering is complete,
the lifecycle method on line 14 (`componentDidUpdate`) will trigger.

This is the final piece of the puzzle: use React's render function
to push an update to the content of the shadow DOM tree. This is
also the reason that we limit the children of our shadow DOM to
a single child: React's rendering system is built to render a root
that has children.

### Trying it Out

A simple test run was added to the devcards of Fulcro in section M10 (Advanced UI),
and the implementation of the shadow DOM component has been added to `fulcro.ui.elements` (as of
1.1.0-SNAPSHOT).

{% highlight clojure linenos %}

(defmutation bump-up [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:child/by-id id :n] inc)))
(defmutation bump-down [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:child/by-id id :n] dec)))

(defui ^:once ListItem
  static prim/InitialAppState
  (initial-state [c {:keys [id n]}] {:id id :n n})
  static om/IQuery
  (query [this] [:id :n])
  static om/Ident
  (ident [this props] [:child/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [id n]} (om/props this)]
      (dom/li #js {:className "item"}
        (dom/span nil "n: " n)
        (dom/button #js {:onClick #(om/transact! this
                                   `[(bump-up {:id ~id})])} "Increment")
        (dom/button #js {:onClick #(om/transact! this
                                   `[(bump-down {:id ~id})])} "Decrement")))))

(def ui-list-item (om/factory ListItem {:keyfn :id}))

(defui ^:once List
  static prim/InitialAppState
  (initial-state [c p]
    {:id 1 :title "My List"
     :items [(prim/get-initial-state ListItem {:id 1 :n 2})
             (prim/get-initial-state ListItem {:id 2 :n 5})
             (prim/get-initial-state ListItem {:id 3 :n 7})]})
  static om/IQuery
  (query [this] [:id :title :max :min {:items (om/get-query ListItem)}])
  static om/Ident
  (ident [this props] [:list/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [title items]} (om/props this)]
      (dom/div #js {:style {:float "left" :width "300px"}}
        (dom/h4 nil (str title))
        (dom/ul nil (map ui-list-item items))))))

(def ui-list (om/factory List {:keyfn :id}))

(defui ^:once SDRoot
  static prim/InitialAppState
  (initial-state [c p] {:lists [(prim/get-initial-state List {})]})
  static om/IQuery
  (query [this] [:ui/react-key {:lists (om/get-query List)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key lists]} (om/props this)]
      (dom/div nil
        (dom/style #js {} ".item {font-size: 40pt}")
        (ele/ui-shadow-dom {:open-boundary? false}
          (dom/div #js {:key react-key}
            (dom/style #js {} ".item {color: red}")
            (map ui-list lists)))
        (dom/div #js {:className "item"} "sibling")))))

{% endhighlight %}

Line 56 in the example above defines a class that would normally bleed
into the list items, and line 59 does a similar thing (it would bleed
out and affect the div with content "sibling").

With the shadow DOM in place, the two are effectively isolated.

There are various polyfills available for browsers that could possibly
make this technique usable now. In my limited testing the above
code worked fine with Chrome and Safari, but failed in Firefox.

As more browsers come on board with the new web components standard
Fulcro will be ready to leverage the new-found isolation, which will
make embedding Fulcro applications inside of existing pages that much
simpler.

