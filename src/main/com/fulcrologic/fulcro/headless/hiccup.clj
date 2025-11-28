(ns com.fulcrologic.fulcro.headless.hiccup
  "Hiccup conversion and utilities for headless testing.

   This namespace provides:
   - Conversion from dom-server Element trees to hiccup format
   - Utility functions for inspecting and traversing hiccup trees
   - Functions operate on hiccup trees directly (not apps)

   Hiccup format: [:tag {:attr \"value\"} child1 child2 ...]

   Example workflow:
   ```clojure
   (require '[com.fulcrologic.fulcro.headless :as h])
   (require '[com.fulcrologic.fulcro.headless.hiccup :as hic])

   ;; Get the rendered tree from a frame
   (let [frame (h/last-frame app)
         hiccup (hic/rendered-tree->hiccup (:rendered frame))]
     ;; Inspect and interact with the hiccup tree
     (hic/find-by-id hiccup \"my-button\")
     (hic/click! (hic/find-by-id hiccup \"submit-btn\")))
   ```"
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.lambda :as lambda]
    [com.fulcrologic.fulcro.dom-server :as dom]))

;; =============================================================================
;; Hiccup Conversion from dom-server Elements
;; =============================================================================

(defprotocol IHiccupConvertible
  "Protocol for converting dom-server elements to hiccup format."
  (to-hiccup* [this] "Convert this element to hiccup."))

(defn- wrap-fn-attrs
  "Wrap any fn? values in attrs with arity-tolerant wrappers.
   This ensures event handlers can be called with any number of arguments,
   matching JavaScript's behavior where extra arguments are ignored."
  [attrs]
  (when attrs
    (persistent!
      (reduce-kv
        (fn [acc k v]
          (assoc! acc k (if (fn? v) (lambda/->arity-tolerant v) v)))
        (transient {})
        attrs))))

(extend-protocol IHiccupConvertible
  com.fulcrologic.fulcro.dom_server.Element
  (to-hiccup* [{:keys [tag attrs children]}]
    (let [wrapped-attrs      (wrap-fn-attrs attrs)
          converted-children (reduce
                               (fn [acc child]
                                 (let [h (to-hiccup* child)]
                                   (if (nil? h)
                                     acc
                                     (if (and (vector? h) (vector? (first h)))
                                       ;; Fragment - flatten into parent
                                       (into acc h)
                                       (conj acc h)))))
                               []
                               children)]
      (into [(keyword tag) (or wrapped-attrs {})] converted-children)))

  com.fulcrologic.fulcro.dom_server.Text
  (to-hiccup* [{:keys [s]}] s)

  com.fulcrologic.fulcro.dom_server.ReactText
  (to-hiccup* [{:keys [text]}] text)

  com.fulcrologic.fulcro.dom_server.ReactEmpty
  (to-hiccup* [_] nil)

  com.fulcrologic.fulcro.dom_server.ReactFragment
  (to-hiccup* [{:keys [elements]}]
    (let [converted (keep to-hiccup* elements)]
      (vec converted)))

  nil
  (to-hiccup* [_] nil))

(defn rendered-tree->hiccup
  "Convert a dom-server Element tree to hiccup format.
   Preserves all attributes including function handlers (unlike render-to-str
   which strips them for HTML output).

   This is the primary conversion function for headless testing where you want
   to inspect the DOM structure and invoke event handlers.

   Accepts:
   - A dom-server Element record
   - A vector of Elements
   - nil

   Returns:
   - A hiccup vector [:tag {...attrs} ...children]
   - Or a vector of hiccup vectors for fragments
   - Or nil for empty elements

   Example:
   ```clojure
   (rendered-tree->hiccup (doms/div {:id \"test\" :onClick my-handler} \"Hello\"))
   ;; => [:div {:id \"test\" :onClick #function} \"Hello\"]
   ```"
  [x]
  (cond
    (nil? x) nil

    (dom/element? x)
    (to-hiccup* x)

    (vector? x)
    (mapv rendered-tree->hiccup x)

    :else
    (throw (IllegalArgumentException.
             (str "Cannot convert to hiccup: " (type x))))))

;; =============================================================================
;; Element Predicates and Accessors
;; =============================================================================

(defn element?
  "Returns true if x is a hiccup element (a vector with a keyword tag).
   Text nodes (strings) and nil are not elements.

   Example:
   ```clojure
   (element? [:div {} \"text\"]) => true
   (element? \"just text\") => false
   ```"
  [x]
  (and (vector? x)
    (keyword? (first x))))

(defn element-tag
  "Get the tag keyword from an element.

   Example:
   ```clojure
   (element-tag [:div {:id \"x\"} \"text\"]) => :div
   ```"
  [elem]
  (when (element? elem)
    (first elem)))

(defn element-attrs
  "Get the attributes map from an element.
   Returns nil if not a valid element or has no attrs map.

   Example:
   ```clojure
   (element-attrs [:div {:id \"x\" :className \"foo\"} \"text\"])
   => {:id \"x\" :className \"foo\"}
   ```"
  [elem]
  (when (element? elem)
    (let [second-item (second elem)]
      (when (map? second-item)
        second-item))))

(defn element-children
  "Get the children of an element (everything after tag and attrs map).

   Example:
   ```clojure
   (element-children [:div {} \"Hello\" [:span {} \"World\"]])
   => [\"Hello\" [:span {} \"World\"]]
   ```"
  [elem]
  (when (element? elem)
    (let [second-item (second elem)]
      (if (map? second-item)
        (subvec elem 2)
        (subvec elem 1)))))

(defn element-attr
  "Get an attribute value from an element.

   Example:
   ```clojure
   (element-attr [:input {:type \"email\" :value \"x@y.com\"}] :type)
   => \"email\"
   ```"
  [elem attr-key]
  (get (element-attrs elem) attr-key))

(defn element-text
  "Get the text content of an element, recursively extracting all text.
   Returns a string with all text content concatenated.

   Example:
   ```clojure
   (element-text [:div {} \"Hello \" [:span {} \"World\"]])
   => \"Hello World\"
   ```"
  [elem]
  (cond
    (nil? elem) ""
    (string? elem) elem
    (number? elem) (str elem)
    (element? elem)
    (apply str (map element-text (element-children elem)))
    (sequential? elem)
    (apply str (map element-text elem))
    :else ""))

(defn element-classes
  "Get the CSS classes of an element as a set.
   Returns an empty set if no classes.

   Example:
   ```clojure
   (element-classes [:div {:className \"btn btn-primary\"}])
   => #{\"btn\" \"btn-primary\"}
   ```"
  [elem]
  (let [attrs     (element-attrs elem)
        class-str (or (:className attrs) (:class attrs) "")]
    (if (str/blank? class-str)
      #{}
      (set (str/split class-str #"\s+")))))

(defn has-class?
  "Returns true if the element has the given CSS class.

   Example:
   ```clojure
   (has-class? [:div {:className \"btn active\"}] \"active\") => true
   ```"
  [elem class-name]
  (contains? (element-classes elem) class-name))

;; =============================================================================
;; Element Finding
;; =============================================================================

(defn find-by-id
  "Find an element in the tree by its :id attribute.
   Returns the first matching element or nil.

   Example:
   ```clojure
   (find-by-id tree \"submit-btn\")
   => [:button {:id \"submit-btn\" :onClick #fn} \"Submit\"]
   ```"
  [tree id]
  (cond
    (nil? tree) nil

    (element? tree)
    (let [attrs (element-attrs tree)]
      (if (= id (:id attrs))
        tree
        (some #(find-by-id % id) (element-children tree))))

    (sequential? tree)
    (some #(find-by-id % id) tree)

    :else nil))

(defn find-all
  "Find all elements in the tree matching the predicate.
   The predicate receives each element (hiccup vector).
   Returns a vector of matching elements.

   Example:
   ```clojure
   (find-all tree #(= :button (element-tag %)))
   => [[:button {...} \"Click\"] [:button {...} \"Cancel\"]]
   ```"
  [tree pred]
  (cond
    (nil? tree) []

    (element? tree)
    (let [matches       (if (pred tree) [tree] [])
          child-matches (mapcat #(find-all % pred) (element-children tree))]
      (into matches child-matches))

    (sequential? tree)
    (vec (mapcat #(find-all % pred) tree))

    :else []))

(defn find-by-tag
  "Find all elements with the given tag keyword.

   Example:
   ```clojure
   (find-by-tag tree :input)
   => [[:input {:type \"text\"...}] [:input {:type \"email\"...}]]
   ```"
  [tree tag]
  (find-all tree #(= tag (element-tag %))))

(defn find-by-class
  "Find all elements with the given CSS class.

   Example:
   ```clojure
   (find-by-class tree \"btn-primary\")
   => [[:button {:className \"btn btn-primary\"} \"Submit\"]]
   ```"
  [tree class-name]
  (find-all tree #(has-class? % class-name)))

(defn find-first
  "Find the first element matching the predicate, or nil.

   Example:
   ```clojure
   (find-first tree #(= :form (element-tag %)))
   => [:form {:onSubmit #fn} ...]
   ```"
  [tree pred]
  (first (find-all tree pred)))

;; =============================================================================
;; Handler Invocation
;; =============================================================================

(defn click!
  "Invoke the :onClick handler of an element.
   The element should have been found via find-by-id or similar.

   The handler will have already closed over the app when the component rendered,
   so no app parameter is needed. Handlers are automatically wrapped to be
   arity-tolerant during hiccup conversion.

   Returns the result of the handler, or nil if no handler.

   Example:
   ```clojure
   (click! (find-by-id tree \"submit-btn\"))
   (click! (find-by-id tree \"checkbox\") {:target {:checked true}})
   ```"
  ([elem] (click! elem {}))
  ([elem event]
   (when elem
     (when-let [on-click (element-attr elem :onClick)]
       (when (fn? on-click)
         (on-click event))))))

(defn invoke-handler!
  "Invoke a specific handler on an element.
   The handler key can be any attribute (e.g., :onClick, :onChange, :onSubmit).

   Returns the result of the handler, or nil if no handler.

   Any additional arguments are applied to the handler, so you can match the arity of the target.

   Example:
   ```clojure
   (invoke-handler! (find-by-id tree \"email\") :onChange {:target {:value \"x@y.com\"}})
   ```"
  [elem handler-key & args]
  (when elem
    (when-let [handler (element-attr elem handler-key)]
      (when (fn? handler)
        (apply handler args)))))

(defn type-text!
  "Invoke the :onChange handler with a text value.
   Convenience for simulating typing into an input.

   Example:
   ```clojure
   (type-text! (find-by-id tree \"username\") \"john.doe\")
   ```"
  [elem value]
  (invoke-handler! elem :onChange {:target {:value value}}))

;; =============================================================================
;; Assertions and Queries
;; =============================================================================

(defn element-exists?
  "Returns true if an element with the given ID exists in the tree.

   Example:
   ```clojure
   (exists? tree \"submit-btn\") => true
   ```"
  [tree id]
  (some? (find-by-id tree id)))

(defn text-of
  "Get the text content of the element with the given ID.
   Returns nil if not found.

   Example:
   ```clojure
   (text-of tree \"counter\") => \"42\"
   ```"
  [tree id]
  (when-let [elem (find-by-id tree id)]
    (element-text elem)))

(defn attr-of
  "Get an attribute value from the element with the given ID.
   Returns nil if element not found or attribute not present.

   Example:
   ```clojure
   (attr-of tree \"email-input\" :type) => \"email\"
   ```"
  [tree id attr-key]
  (when-let [elem (find-by-id tree id)]
    (element-attr elem attr-key)))

(defn classes-of
  "Get the CSS classes of the element with the given ID as a set.
   Returns nil if element not found.

   Example:
   ```clojure
   (classes-of tree \"my-btn\") => #{\"btn\" \"btn-primary\"}
   ```"
  [tree id]
  (when-let [elem (find-by-id tree id)]
    (element-classes elem)))

;; =============================================================================
;; Text-Based Finding
;; =============================================================================

(defn- text-pattern-matches?
  "Check if the given text matches the pattern.
   Pattern can be a string (substring match) or regex."
  [text pattern]
  (when text
    (cond
      (string? pattern) (str/includes? (str text) pattern)
      (instance? java.util.regex.Pattern pattern) (boolean (re-find pattern (str text)))
      :else false)))

(defn- patterns-match?
  "Check if text matches the pattern(s).
   If pattern is a vector, all patterns must match."
  [text pattern]
  (cond
    (nil? text) false
    (vector? pattern) (every? #(text-pattern-matches? text %) pattern)
    :else (text-pattern-matches? text pattern)))

(defn- direct-text-content
  "Get only the direct text children of an element (not text from nested elements).
   This is the text that appears directly within the element, not in child elements."
  [elem]
  (when (element? elem)
    (let [children (element-children elem)]
      (apply str (filter string? children)))))

(defn- element-matches-text-pattern?
  "Check if an element matches the text pattern.
   Matches on:
   - Direct text content of the element (not nested element text)
   - The :text attribute
   - The :label attribute
   - The full element-text (for backwards compatibility with simple cases)"
  [elem pattern]
  (let [direct-text (direct-text-content elem)
        full-text   (element-text elem)
        attrs       (element-attrs elem)]
    (or (patterns-match? (:text attrs) pattern)
      (patterns-match? (:label attrs) pattern)
      (patterns-match? direct-text pattern)
      ;; Fall back to full text only if there are no child elements
      ;; (i.e., this is a leaf text node container)
      (and (not-any? element? (element-children elem))
        (patterns-match? full-text pattern)))))

(defn find-by-text
  "Find all elements whose text content matches the pattern.
   Pattern can be:
   - A string (substring match)
   - A regex (pattern match)
   - A vector of patterns (all must match)

   Also checks :text and :label attributes on elements.

   Returns only the most specific matching elements - parent containers are
   excluded if they only match because their children contain the text.

   Example:
   ```clojure
   (find-by-text tree \"View All\")
   (find-by-text tree #\"Account.*\")
   (find-by-text tree [\"Account\" \"View\"])  ; must contain both
   ```"
  [tree pattern]
  (find-all tree #(element-matches-text-pattern? % pattern)))

(defn find-first-by-text
  "Find the first element matching the text pattern, or nil.
   See `find-by-text` for pattern options."
  [tree pattern]
  (first (find-by-text tree pattern)))

(defn find-nth-by-text
  "Find the nth element (0-indexed) matching the text pattern, or nil.
   See `find-by-text` for pattern options."
  [tree pattern n]
  (nth (find-by-text tree pattern) n nil))

;; =============================================================================
;; Path-Aware Finding (for bubbling)
;; =============================================================================

(defn- find-with-path
  "Find elements matching predicate, returning each with its path from root.
   Returns seq of {:element elem :path [root ... parent elem]}."
  [tree pred]
  (letfn [(search [node path]
            (cond
              (nil? node) []

              (element? node)
              (let [new-path      (conj path node)
                    self-match    (when (pred node) [{:element node :path new-path}])
                    child-results (mapcat #(search % new-path) (element-children node))]
                (concat (or self-match []) child-results))

              (sequential? node)
              (mapcat #(search % path) node)

              :else []))]
    (search tree [])))

;; =============================================================================
;; Label-Based Input Finding
;; =============================================================================

(defn find-input-for-label
  "Find an input element associated with a label matching the pattern.

   Searches for:
   1. A container element that has both a label (or element with :label attr)
      matching the pattern AND an input element
   2. Returns the input from the smallest (most deeply nested) such container

   Pattern can be a string, regex, or vector of patterns.

   Example:
   ```clojure
   (find-input-for-label tree \"Username\")
   (find-input-for-label tree #\"(?i)email\")
   ```"
  [tree label-pattern]
  (let [has-matching-label? (fn [elem]
                              (or (patterns-match? (:label (element-attrs elem)) label-pattern)
                                (some #(patterns-match? (element-text %) label-pattern)
                                  (find-by-tag elem :label))))
        containers          (find-all tree
                              (fn [elem]
                                (and (has-matching-label? elem)
                                  (seq (find-by-tag elem :input)))))]
    ;; Take the last (deepest) container since find-all returns parents before children
    (when-let [container (last containers)]
      (first (find-by-tag container :input)))))

(defn find-nth-input-for-label
  "Find the nth input (0-indexed) associated with labels matching the pattern.

   Useful when multiple fields have similar labels.

   Example:
   ```clojure
   ;; Get the second 'Amount' field
   (find-nth-input-for-label tree \"Amount\" 1)
   ```"
  [tree label-pattern n]
  (let [has-matching-label? (fn [elem]
                              (or (patterns-match? (:label (element-attrs elem)) label-pattern)
                                (some #(patterns-match? (element-text %) label-pattern)
                                  (find-by-tag elem :label))))
        containers          (find-all tree
                              (fn [elem]
                                (and (has-matching-label? elem)
                                  (seq (find-by-tag elem :input)))))
        ;; Group consecutive containers - we want distinct field groups
        ;; The deepest container in each subtree has the actual input
        grouped             (reduce
                              (fn [acc container]
                                (let [input (first (find-by-tag container :input))]
                                  (if (some #(= input (first (find-by-tag % :input))) acc)
                                    acc
                                    (conj acc container))))
                              []
                              (reverse containers))]
    (when-let [container (nth (reverse grouped) n nil)]
      (first (find-by-tag container :input)))))

;; =============================================================================
;; Bubbling Click Handlers
;; =============================================================================

(defn click-bubbling!
  "Find an element by text and invoke onClick, bubbling up to ancestors if necessary.
   Returns the result of the handler, or nil if no clickable element found.

   This implements event bubbling semantics: if the matched element doesn't have
   an onClick handler, we walk up the ancestor chain to find one.

   The `n` parameter specifies which match to click (0-indexed, default 0).

   Pattern can be a string, regex, or vector of patterns.

   Example:
   ```clojure
   (click-bubbling! tree \"View All\")
   (click-bubbling! tree \"New\" 1)  ; click second 'New' link
   (click-bubbling! tree #\"Logout\")
   ```"
  ([tree pattern] (click-bubbling! tree pattern 0))
  ([tree pattern n]
   (let [matches (find-with-path tree #(element-matches-text-pattern? % pattern))
         match   (nth matches n nil)]
     (when match
       (let [{:keys [path]} match
             ;; Walk up from element (last in path) looking for onClick
             elements-to-check (reverse path)
             clickable         (first (filter #(fn? (element-attr % :onClick)) elements-to-check))]
         (when clickable
           (click! clickable)))))))

(defn type-text-into-labeled!
  "Find an input by its label and type text into it.
   Invokes the input's :onChange handler with {:target {:value text}}.

   The `n` parameter specifies which matching labeled field to use (0-indexed, default 0).

   Example:
   ```clojure
   (type-text-into-labeled! tree \"Username\" \"john.doe\")
   (type-text-into-labeled! tree \"Password\" \"secret123\")
   (type-text-into-labeled! tree \"Amount\" \"100.00\" 1)  ; second Amount field
   ```"
  ([tree label-pattern value] (type-text-into-labeled! tree label-pattern value 0))
  ([tree label-pattern value n]
   (when-let [input (find-nth-input-for-label tree label-pattern n)]
     (type-text! input value))))
