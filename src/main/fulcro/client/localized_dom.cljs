(ns fulcro.client.localized-dom
  (:refer-clojure :exclude [map meta time use set symbol filter])
  (:require
    fulcro.client.dom
    [fulcro.util :as util]
    [fulcro.client.localized-dom-common :as cdom]))

(declare a abbr address altGlyph altGlyphDef altGlyphItem animate animateColor animateMotion animateTransform area
  article aside audio b base bdi bdo big blockquote body br button canvas caption circle cite clipPath code
  col colgroup color-profile cursor data datalist dd defs del desc details dfn dialog discard div dl dt
  ellipse em embed feBlend feColorMatrix feComponentTransfer feComposite feConvolveMatrix feDiffuseLighting
  feDisplacementMap feDistantLight feDropShadow feFlood feFuncA feFuncB feFuncG feFuncR feGaussianBlur
  feImage feMerge feMergeNode feMorphology feOffset fePointLight feSpecularLighting feSpotLight feTile feTurbulence
  fieldset figcaption figure filter font font-face font-face-format font-face-name font-face-src font-face-uri
  footer foreignObject form g glyph glyphRef h1 h2 h3 h4 h5 h6 hatch hatchpath head header hkern hr html
  i iframe image img input ins kbd keygen label legend li line linearGradient link main map mark marker mask
  menu menuitem mesh meshgradient meshpatch meshrow meta metadata meter missing-glyph
  mpath nav noscript object ol optgroup option output p param path pattern picture polygon polyline pre progress q radialGradient
  rect rp rt ruby s samp script section select set small solidcolor source span stop strong style sub summary
  sup svg switch symbol table tbody td text textPath textarea tfoot th thead time title tr track tref tspan
  u ul unknown use var video view vkern wbr)

(def node fulcro.client.dom/node)
(def render-to-str fulcro.client.dom/render-to-str)
(def create-element fulcro.client.dom/create-element)

(letfn [(arr-append* [arr x] (.push arr x) arr)
        (arr-append [arr tail] (reduce arr-append* arr (util/force-children tail)))]
  (defn macro-create-element
    ([type args] (macro-create-element type args nil))
    ([type args csskw]
     (let [[head & tail] args
           f (if (fulcro.client.dom/form-elements? type)
               fulcro.client.dom/macro-create-wrapped-form-element
               fulcro.client.dom/macro-create-element*)]

       (cond
         (nil? head)
         (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
              (arr-append tail)))

         (fulcro.client.dom/element? head)
         (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
              (arr-append args)))

         (object? head)
         (f (doto #js [type (cdom/add-kwprops-to-props head csskw)]
              (arr-append tail)))

         (map? head)
         (f (doto #js [type (clj->js (cdom/add-kwprops-to-props head csskw))]
              (arr-append tail)))

         :else
         (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
              (arr-append args))))))))

(fulcro.client.dom/gen-client-dom-fns fulcro.client.localized-dom/macro-create-element)
