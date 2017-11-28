(ns cards.component-localized-css
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
    [fulcro-css.css :as css]
    [fulcro.client.primitives :as prim :refer [defui]]
    [fulcro.client.dom :as dom]))

(defonce theme-color (atom :blue))

(defui ^:once Child
  static css/CSS
  ; define local rules via garden. Defined names will be auto-localized
  (local-rules [this] [[:.thing {:color @theme-color}]])
  (include-children [this] [])
  Object
  (render [this]
    (let [{:keys [label]} (prim/props this)
          ; use destructuring against the name you invented to get the localized classname
          {:keys [thing]} (css/get-classnames Child)]
      (dom/div #js {:className thing} label))))

(def ui-child (prim/factory Child))

(declare change-color)
(defui ^:once Root
  static css/CSS
  (local-rules [this] [])
  ; Compose children with local reasoning. Dedupe is automatic if two UI paths cause re-inclusion.
  (include-children [this] [Child])
  static prim/IQuery
  (query [this] [:ui/react-key])
  Object
  (render [this]
    (let [{:keys [ui/react-key child]} (prim/props this)]
      (dom/div #js {:key react-key}
        (dom/button #js {:onClick (fn [e]
                                    ; change the atom, and re-upsert the CSS. Look at the elements in your dev console.
                                    ; Figwheel and Closure push SCRIPT tags too, so it may be hard to find on
                                    ; initial load. You might try clicking one of these
                                    ; to make it easier to find (the STYLE will pop to the bottom).
                                    (change-color "blue"))} "Use Blue Theme")
        (dom/button #js {:onClick (fn [e]
                                    (change-color "red"))} "Use Red Theme")
        (ui-child {:label "Hello World"})))))

(defn change-color [c]
  (reset! theme-color c)
  (css/upsert-css "css-id" Root))

; Push the real CSS to the DOM via a component. One or more of these could be done to, for example,
; include CSS from different modules or libraries into different style elements.
(css/upsert-css "css-id" Root)

(dc/defcard-doc
  "# Colocated CSS

   The fulcro-css library adds extensions that make it easy to co-locate and compose localized
   component CSS with your UI! This leads to all sorts of wonderful and interesting
   results:

   - Your CSS is name-localized, so your component's CSS is effectively private
   - You don't have to go find the CSS file to include it in your application. It's already in the code.
   - Since it's code, you can use data manipualtion, variables, and more to create your CSS. It
   is the ultimate in code reuse and generality.
   - The composition facilities prevent duplication.
   - It becomes much easier to allow themed components with additional tool chains! Variables can be
  supplied that can simply be modified via code before CSS injection.
  - Closure minification can be applied to reduce the size of the resulting files during your
  normal compilation!
  - Server-side rendering can pre-inject CSS rules into the server-side rendered page! No more waiting for
  your CSS to load for the UI to look right!

  Basically components define local rules via [garden](https://github.com/noprompt/garden) syntax, and Fulcro
  will localize the class names using the namespace and component name.
  Parent components compose in the children via `include-children` for the components that it uses.
  Just like initial state and queries. This causes all used components to compose to the root. Actually,
  you can stop at any time and extract CSS from any component, so you need not compose it to the root if
  you don't want/need to.

  Two support functions then give you easy access to the generated CSS and generated names.

  To access the localized names for a component, just use:

  ```
  (let [{:keys [simple-name]} (css/get-classnames Child)] ...)
  ```

  This function returns a map from the *simple* name you used in defining a rule with the value
  of the *localized* name that is in the CSS. This allows you to be unaware of the localization rules!

  At some point in your code (e.g. startup) you will want to inject the CSS into the DOM. Typically, with:

  ```
  (css/upsert-css \"dom-id\" Component)
  ```

  Any number of those can be done, at whatever ID you care to invent (it will replace or insert a style element
  as necessary).

  The card below upserts the co-located CSS in the code in `recipes.colocated-css` file itself, but it also
  bases the color on the value in an atom (think theme color). At any time you can change your various
  embedded data on colocated CSS and re-upsert the generated result! For example, at a REPL you could run:

   ```
   (recipes.colocated-css/change-color \"red\")
   ```

  and the color of the text will change. Look near the bottom of the HTML Elements in developers console
  for a STYLE element with the ID css-id to watch it change.

  The complete commented code for the example is:
  "
  (dc/mkdn-pprint-source Child)
  (dc/mkdn-pprint-source Root)
  (dc/mkdn-pprint-source theme-color)
  (dc/mkdn-pprint-source change-color))

(defcard-fulcro colocated-css Root)
