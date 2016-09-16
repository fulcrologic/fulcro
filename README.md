# Om-CSS A library to help generate co-located CSS on Om and Untangled components.

This library provides some utility functions that help you use 
[garden](https://github.com/noprompt/garden) for co-located, localized
component CSS. 

<a href="https://clojars.org/untangled/om-css">
<img src="https://clojars.org/untangled/om-css/latest-version.svg">
</a>

## Usage

First, co-locate your rules on the components, and use the localized class
names in your rendering. The primary tools for this are garden syntax,
`css/local-kw` to generate localized classname keywords for garden,
`css/local-class` to generate localized classname strings for use in
the `:className` attribute of DOM elements, and `localize-classnames`
which is a macro that will rewrite a render body from simple a `:class`
attribute to the proper `:className` attribute.

### Component samples 

```
(ns my-ns
  (:require 
     [om-css.core :as css :refer-macros [localize-classnames]
     [om.next :as om :refer-macros [defui]]))
  
(defui Component
  css/CSS
  (css [this] [ [(css/local-kw Component :class1) {:color 'blue}] 
                [(css/local-kw Component :class2) {:color 'blue}] ])
  Object
  (render [this]
    ; can use a macro to rewrite classnames. $ is used to prevent localization. Note the use of :class instead of :className
    (localize-classnames Component
       (dom/div #js {:class [:class1 :class2 :$root-class]} ...))))
       
(defui Component2
  css/CSS
  ; CSS rules can be composed from children and additional garden rules:
  (css [this] (css/css-merge 
                 Component 
                 [(css/local-kw Component2 :class) {:color 'red}]))
  Object
  (render [this]
    ; there is a helper function if you just want to get the munged classname
    (dom/div #js {:className (css/local-class Component2 :class) } ...)))
```

### Emitting your styles to the page

There are two methods for putting your co-located styles into your 
application:

- Emit a `dom/style` element in your Root UI component. For example:
  `(dom/style nil (garden.core/css (om-css.core/css Root)))`. The problem with this
  approach is that your root element itself will not see all of the CSS, since the style is embedded within it.
- Force a style element out to the DOM document. There is a helper function `om-css.core/upsert-css` that can
  be called somewhere in your application initialization. It will extract the CSS and put it in a style element. If that 
  style element already exists then it will replace it, meaning that you can use it in the namespace that figwheel always
  reloads as a way to refresh the CSS during development.

### Allowing external users to customize the CSS rules

One intention of this co-located CSS is to enable component libraries to come with CSS
that is easy to configure and use. Since the CSS is written as code you can use
things like atoms to represent colors, sizes, etc. Simply provide some helper functions
that allow a user to set things like colors and such, and use the resulting values 
in the co-located CSS generation.

# More Information

Coming soon: See the Untangled Cookbook css recipe for a working example.
