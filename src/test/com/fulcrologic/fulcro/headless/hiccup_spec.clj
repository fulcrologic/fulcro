(ns com.fulcrologic.fulcro.headless.hiccup-spec
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom-server :as dom]
    [com.fulcrologic.fulcro.headless.hiccup :as hic]
    [fulcro-spec.core :refer [=> assertions specification]]))

;; =============================================================================
;; rendered-tree->hiccup Tests
;; =============================================================================

(specification "rendered-tree->hiccup"
  (assertions
    "Simple element conversion"
    (hic/rendered-tree->hiccup (dom/div {} "Hello"))
    => [:div {} "Hello"]

    "Element with attributes"
    (hic/rendered-tree->hiccup (dom/div {:id "test" :className "foo"} "Content"))
    => [:div {:id "test" :className "foo"} "Content"]

    "Nested elements"
    (hic/rendered-tree->hiccup (dom/div {} (dom/span {} "Inner")))
    => [:div {} [:span {} "Inner"]]

    "Multiple children"
    (hic/rendered-tree->hiccup (dom/div {} (dom/p "First") (dom/p "Second")))
    => [:div {} [:p {} "First"] [:p {} "Second"]]

    "CSS shorthand is preserved in attrs"
    (hic/rendered-tree->hiccup (dom/div :.my-class#my-id "Content"))
    => [:div {:className "my-class" :id "my-id"} "Content"]

    "Nil returns nil"
    (hic/rendered-tree->hiccup nil)
    => nil))

(specification "rendered-tree->hiccup preserves function handlers"
  (let [click-handler  (fn [e] :clicked)
        change-handler (fn [e] :changed)
        elem           (dom/button {:id "btn" :onClick click-handler :onChange change-handler} "Click")
        hiccup         (hic/rendered-tree->hiccup elem)
        attrs          (second hiccup)]
    (assertions
      "onClick is a function"
      (fn? (:onClick attrs)) => true
      "onClick returns expected value"
      ((:onClick attrs) {}) => :clicked
      "onChange is a function"
      (fn? (:onChange attrs)) => true
      "onChange returns expected value"
      ((:onChange attrs) {}) => :changed)))

(specification "rendered-tree->hiccup with nested elements"
  (assertions
    "Simple nested element converts to hiccup"
    (hic/rendered-tree->hiccup (dom/div {} "Hello"))
    => [:div {} "Hello"]

    "Nested elements in wrapper"
    (hic/rendered-tree->hiccup (dom/div {:className "wrapper"} (dom/div {} "Hello")))
    => [:div {:className "wrapper"} [:div {} "Hello"]]

    "Element with dynamic content"
    (hic/rendered-tree->hiccup (dom/div {} (str 2)))
    => [:div {} "2"]))

(specification "rendered-tree->hiccup with fragments"
  (assertions
    "Fragment inside element flattens children"
    (hic/rendered-tree->hiccup (dom/div {} (comp/fragment (dom/p "a") (dom/p "b"))))
    => [:div {} [:p {} "a"] [:p {} "b"]]

    "Top-level fragment converts to vector of hiccup"
    (hic/rendered-tree->hiccup (comp/fragment (dom/div {} "A") (dom/div {} "B")))
    => [[:div {} "A"] [:div {} "B"]]

    "Vector of elements converts to vector of hiccup"
    (hic/rendered-tree->hiccup [(dom/div {} "A") (dom/div {} "B")])
    => [[:div {} "A"] [:div {} "B"]]))

;; =============================================================================
;; Element Utilities Tests
;; =============================================================================

(specification "element?"
  (assertions
    "Returns true for hiccup vectors"
    (hic/element? [:div {} "text"]) => true
    (hic/element? [:span {:id "x"}]) => true

    "Returns false for non-elements"
    (hic/element? "just text") => false
    (hic/element? nil) => false
    (hic/element? 42) => false
    (hic/element? {}) => false))

(specification "element-tag"
  (assertions
    "Returns the tag keyword"
    (hic/element-tag [:div {:id "x"} "text"]) => :div
    (hic/element-tag [:span {}]) => :span

    "Returns nil for non-elements"
    (hic/element-tag "text") => nil))

(specification "element-attrs"
  (assertions
    "Returns the attrs map"
    (hic/element-attrs [:div {:id "x" :className "foo"} "text"])
    => {:id "x" :className "foo"}

    "Returns nil for non-elements"
    (hic/element-attrs "text") => nil))

(specification "element-children"
  (assertions
    "Returns children after attrs"
    (hic/element-children [:div {} "Hello" [:span {} "World"]])
    => ["Hello" [:span {} "World"]]

    "Works with no children"
    (hic/element-children [:div {}]) => []))

(specification "element-text"
  (assertions
    "Extracts text from simple element"
    (hic/element-text [:div {} "Hello"]) => "Hello"

    "Concatenates nested text"
    (hic/element-text [:div {} "Hello " [:span {} "World"]]) => "Hello World"

    "Handles numbers"
    (hic/element-text [:div {} 42]) => "42"

    "Returns empty string for nil"
    (hic/element-text nil) => ""))

(specification "element-classes"
  (assertions
    "Returns classes as set"
    (hic/element-classes [:div {:className "btn btn-primary"}])
    => #{"btn" "btn-primary"}

    "Returns empty set for no classes"
    (hic/element-classes [:div {:id "x"}]) => #{}))

(specification "has-class?"
  (assertions
    "Returns true when class present"
    (hic/has-class? [:div {:className "btn active"}] "active") => true

    "Returns false when class absent"
    (hic/has-class? [:div {:className "btn"}] "active") => false))

;; =============================================================================
;; Finding Tests
;; =============================================================================

(specification "find-by-id"
  (let [tree [:div {}
              [:button {:id "btn-1"} "First"]
              [:div {}
               [:button {:id "btn-2"} "Second"]]]]
    (assertions
      "Finds element by id"
      (hic/find-by-id tree "btn-1") => [:button {:id "btn-1"} "First"]

      "Finds nested element"
      (hic/find-by-id tree "btn-2") => [:button {:id "btn-2"} "Second"]

      "Returns nil when not found"
      (hic/find-by-id tree "nonexistent") => nil)))

(specification "find-by-tag"
  (let [tree [:div {}
              [:button {} "A"]
              [:span {} "B"]
              [:button {} "C"]]]
    (assertions
      "Finds all elements with tag"
      (hic/find-by-tag tree :button)
      => [[:button {} "A"] [:button {} "C"]]

      "Returns empty when none found"
      (hic/find-by-tag tree :input) => [])))

(specification "find-by-class"
  (let [tree [:div {}
              [:button {:className "btn primary"} "A"]
              [:span {:className "label"} "B"]
              [:button {:className "btn secondary"} "C"]]]
    (assertions
      "Finds all elements with class"
      (count (hic/find-by-class tree "btn")) => 2

      "Returns empty when none found"
      (hic/find-by-class tree "nonexistent") => [])))

;; =============================================================================
;; Handler Invocation Tests
;; =============================================================================

(specification "click!"
  (let [clicked (atom false)
        elem    [:button {:id "btn" :onClick (fn [_] (reset! clicked true))} "Click"]]
    (hic/click! elem)
    (assertions
      "Invokes onClick handler"
      @clicked => true)))

(specification "invoke-handler!"
  (let [value (atom nil)
        elem  [:input {:id "inp" :onChange (fn [e] (reset! value (:value (:target e))))}]]
    (hic/invoke-handler! elem :onChange {:target {:value "test"}})
    (assertions
      "Invokes specified handler with event"
      @value => "test")))

(specification "type-text!"
  (let [value (atom nil)
        elem  [:input {:onChange (fn [e] (reset! value (:value (:target e))))}]]
    (hic/type-text! elem "hello")
    (assertions
      "Invokes onChange with value"
      @value => "hello")))

;; =============================================================================
;; Query Utilities Tests
;; =============================================================================

(specification "exists?"
  (let [tree [:div {} [:button {:id "btn"} "Click"]]]
    (assertions
      "Returns true when element exists"
      (hic/element-exists? tree "btn") => true

      "Returns false when element doesn't exist"
      (hic/element-exists? tree "nonexistent") => false)))

(specification "text-of"
  (let [tree [:div {} [:span {:id "msg"} "Hello World"]]]
    (assertions
      "Returns text of element"
      (hic/text-of tree "msg") => "Hello World"

      "Returns nil when not found"
      (hic/text-of tree "nonexistent") => nil)))

(specification "attr-of"
  (let [tree [:div {} [:input {:id "email" :type "email" :value "test@test.com"}]]]
    (assertions
      "Returns attribute value"
      (hic/attr-of tree "email" :type) => "email"
      (hic/attr-of tree "email" :value) => "test@test.com"

      "Returns nil when element not found"
      (hic/attr-of tree "nonexistent" :type) => nil)))

(specification "classes-of"
  (let [tree [:div {} [:button {:id "btn" :className "btn btn-primary"}]]]
    (assertions
      "Returns classes as set"
      (hic/classes-of tree "btn") => #{"btn" "btn-primary"}

      "Returns nil when not found"
      (hic/classes-of tree "nonexistent") => nil)))

;; =============================================================================
;; Arity-Tolerant Auto-Wrapping Tests
;; =============================================================================

(specification "fn attributes are automatically arity-tolerant"
  (let [call-count (atom 0)
        handler    (fn [] (swap! call-count inc))           ; 0-arity handler
        elem       (dom/button {:id "test" :onClick handler} "Click")
        hiccup     (hic/rendered-tree->hiccup elem)
        wrapped    (hic/element-attr hiccup :onClick)]
    ;; Should be able to call with event arg even though handler takes 0 args
    (wrapped {:type "click"})
    (assertions
      "Zero-arity handler can be called with event arg"
      @call-count => 1)))

(specification "arity-tolerant wrapping with single-arity handlers"
  (let [received (atom nil)
        handler  (fn [e] (reset! received e))               ; 1-arity handler
        elem     (dom/button {:id "test" :onClick handler} "Click")
        hiccup   (hic/rendered-tree->hiccup elem)
        wrapped  (hic/element-attr hiccup :onClick)]
    (wrapped {:type "click"} :extra :ignored :args)
    (assertions
      "Single-arity handler receives first arg, extras dropped"
      @received => {:type "click"})))

(specification "arity-tolerant wrapping preserves non-fn attributes"
  (let [elem   (dom/div {:id "test" :className "foo" :data-value 42 :disabled true})
        hiccup (hic/rendered-tree->hiccup elem)]
    (assertions
      "String attributes preserved"
      (hic/element-attr hiccup :id) => "test"
      (hic/element-attr hiccup :className) => "foo"

      "Number attributes preserved"
      (hic/element-attr hiccup :data-value) => 42

      "Boolean attributes preserved"
      (hic/element-attr hiccup :disabled) => true)))

(specification "arity-tolerant wrapping with nil attrs"
  (let [elem   (dom/div {} "content")
        hiccup (hic/rendered-tree->hiccup elem)]
    (assertions
      "Empty attrs work"
      (hic/element-attrs hiccup) => {})))

(specification "click! works with zero-arity handlers"
  (let [clicked (atom false)
        handler (fn [] (reset! clicked true))               ; 0-arity
        elem    (dom/button {:id "btn" :onClick handler} "Click")
        hiccup  (hic/rendered-tree->hiccup elem)
        btn     (hic/find-by-id hiccup "btn")]
    (hic/click! btn {:type "click"})                        ; Passing event to 0-arity handler
    (assertions
      "click! works even when handler takes no args"
      @clicked => true)))

(specification "invoke-handler! works with mismatched arities"
  (let [value   (atom nil)
        handler (fn [] (reset! value :called))              ; 0-arity onChange
        elem    (dom/input {:id "inp" :onChange handler})
        hiccup  (hic/rendered-tree->hiccup elem)
        input   (hic/find-by-id hiccup "inp")]
    (hic/invoke-handler! input :onChange {:target {:value "test"}})
    (assertions
      "Handler invoked despite arity mismatch"
      @value => :called)))

(specification "type-text! works with zero-arity onChange"
  (let [called  (atom false)
        handler (fn [] (reset! called true))                ; 0-arity onChange
        elem    (dom/input {:id "inp" :onChange handler})
        hiccup  (hic/rendered-tree->hiccup elem)
        input   (hic/find-by-id hiccup "inp")]
    (hic/type-text! input "hello")
    (assertions
      "type-text! works with 0-arity handler"
      @called => true)))

;; =============================================================================
;; Text-Based Finding Tests
;; =============================================================================

(specification "find-by-text"
  (let [tree [:div {}
              [:button {:id "btn-1"} "View All"]
              [:div {}
               [:span {} "Hello World"]
               [:button {:id "btn-2"} "View All"]]
              [:Dropdown {:text "Account"} "Menu Content"]]]
    (assertions
      "Finds elements by exact text match"
      (count (hic/find-by-text tree "View All")) => 2

      "Finds elements by substring"
      (count (hic/find-by-text tree "Hello")) => 1

      "Finds elements by :text attribute"
      (count (hic/find-by-text tree "Account")) => 1

      "Returns empty vector when not found"
      (hic/find-by-text tree "Nonexistent") => [])))

(specification "find-by-text with regex"
  (let [tree [:div {}
              [:span {} "Error: Something went wrong"]
              [:span {} "Warning: Check this"]
              [:span {} "Info: All good"]]]
    (assertions
      "Finds elements matching regex"
      (count (hic/find-by-text tree #"Error:.*")) => 1
      (count (hic/find-by-text tree #"(?i)warning")) => 1

      "Regex matches substring"
      (count (hic/find-by-text tree #"^Info")) => 1)))

(specification "find-by-text with vector pattern"
  (let [tree [:div {}
              [:div {} "Account View All"]
              [:div {} "Account New"]
              [:div {} "Inventory View All"]]]
    (assertions
      "Vector pattern requires all patterns to match"
      (count (hic/find-by-text tree ["Account" "View"])) => 1
      (count (hic/find-by-text tree ["Account"])) => 2
      (count (hic/find-by-text tree ["View" "All"])) => 2)))

(specification "find-first-by-text"
  (let [tree [:div {}
              [:button {} "First"]
              [:button {} "Second"]
              [:button {} "First"]]]
    (assertions
      "Returns first matching element"
      (hic/element-text (hic/find-first-by-text tree "First")) => "First"

      "Returns nil when not found"
      (hic/find-first-by-text tree "Nonexistent") => nil)))

(specification "find-nth-by-text"
  (let [tree [:div {}
              [:button {:id "b1"} "Click Me"]
              [:button {:id "b2"} "Click Me"]
              [:button {:id "b3"} "Click Me"]]]
    (assertions
      "Returns nth matching element (0-indexed)"
      (hic/element-attr (hic/find-nth-by-text tree "Click Me" 0) :id) => "b1"
      (hic/element-attr (hic/find-nth-by-text tree "Click Me" 1) :id) => "b2"
      (hic/element-attr (hic/find-nth-by-text tree "Click Me" 2) :id) => "b3"

      "Returns nil for out of bounds"
      (hic/find-nth-by-text tree "Click Me" 10) => nil)))

;; =============================================================================
;; Label-Based Input Finding Tests
;; =============================================================================

(specification "find-input-for-label"
  (let [tree [:div {}
              [:div {:className "field"}
               [:label {} "Username"]
               [:input {:id "username" :type "text"}]]
              [:div {:className "field"}
               [:label {} "Password"]
               [:input {:id "password" :type "password"}]]]]
    (assertions
      "Finds input by label text"
      (hic/element-attr (hic/find-input-for-label tree "Username") :id) => "username"
      (hic/element-attr (hic/find-input-for-label tree "Password") :id) => "password"

      "Returns nil when label not found"
      (hic/find-input-for-label tree "Nonexistent") => nil)))

(specification "find-input-for-label with :label attribute"
  (let [tree [:div {}
              [:CustomField {:label "Email Address"}
               [:input {:id "email" :type "email"}]]]]
    (assertions
      "Finds input by :label attribute on parent"
      (hic/element-attr (hic/find-input-for-label tree "Email") :id) => "email")))

(specification "find-input-for-label with regex"
  (let [tree [:div {}
              [:div {:className "field"}
               [:label {} "Email Address"]
               [:input {:id "email"}]]]]
    (assertions
      "Finds input by regex pattern on label"
      (hic/element-attr (hic/find-input-for-label tree #"(?i)email") :id) => "email")))

(specification "find-nth-input-for-label"
  (let [tree [:div {}
              [:div {:className "field"}
               [:label {} "Amount"]
               [:input {:id "amount-1"}]]
              [:div {:className "field"}
               [:label {} "Amount"]
               [:input {:id "amount-2"}]]
              [:div {:className "field"}
               [:label {} "Amount"]
               [:input {:id "amount-3"}]]]]
    (assertions
      "Returns nth input with matching label (0-indexed)"
      (hic/element-attr (hic/find-nth-input-for-label tree "Amount" 0) :id) => "amount-1"
      (hic/element-attr (hic/find-nth-input-for-label tree "Amount" 1) :id) => "amount-2"
      (hic/element-attr (hic/find-nth-input-for-label tree "Amount" 2) :id) => "amount-3"

      "Returns nil for out of bounds"
      (hic/find-nth-input-for-label tree "Amount" 10) => nil)))

;; =============================================================================
;; Bubbling Click Tests
;; =============================================================================

(specification "click-bubbling!"
  (let [clicked (atom nil)
        tree    [:div {}
                 [:DropdownItem {:onClick (fn [_] (reset! clicked :item))} "View All"]
                 [:button {:onClick (fn [_] (reset! clicked :button))} "Submit"]]]
    (hic/click-bubbling! tree "View All")
    (assertions
      "Clicks element with matching text"
      @clicked => :item)

    (reset! clicked nil)
    (hic/click-bubbling! tree "Submit")
    (assertions
      "Clicks button with matching text"
      @clicked => :button)))

(specification "click-bubbling! with event bubbling"
  (let [clicked (atom nil)
        ;; Text is in child, onClick is on parent
        tree    [:div {:onClick (fn [_] (reset! clicked :parent))}
                 [:span {} "Click Target"]]]
    (hic/click-bubbling! tree "Click Target")
    (assertions
      "Bubbles up to find onClick on ancestor"
      @clicked => :parent)))

(specification "click-bubbling! with nth parameter"
  (let [clicked (atom nil)
        tree    [:div {}
                 [:button {:onClick (fn [_] (reset! clicked :first))} "Action"]
                 [:button {:onClick (fn [_] (reset! clicked :second))} "Action"]
                 [:button {:onClick (fn [_] (reset! clicked :third))} "Action"]]]
    (hic/click-bubbling! tree "Action" 0)
    (assertions "Clicks first match by default" @clicked => :first)

    (hic/click-bubbling! tree "Action" 1)
    (assertions "Clicks second match with n=1" @clicked => :second)

    (hic/click-bubbling! tree "Action" 2)
    (assertions "Clicks third match with n=2" @clicked => :third)))

(specification "click-bubbling! with :text attribute"
  (let [clicked (atom nil)
        tree    [:Dropdown {:text "Account" :onClick (fn [_] (reset! clicked :dropdown))}
                 [:DropdownMenu {} "Menu items"]]]
    (hic/click-bubbling! tree "Account")
    (assertions
      "Finds element by :text attribute and clicks"
      @clicked => :dropdown)))

;; =============================================================================
;; Type Into Labeled Field Tests
;; =============================================================================

(specification "type-text-into-labeled!"
  (let [value (atom nil)
        tree  [:div {}
               [:div {:className "field"}
                [:label {} "Username"]
                [:input {:onChange (fn [e] (reset! value (-> e :target :value)))}]]]]
    (hic/type-text-into-labeled! tree "Username" "john.doe")
    (assertions
      "Types into input found by label"
      @value => "john.doe")))

(specification "type-text-into-labeled! with nth parameter"
  (let [values (atom {})
        tree   [:div {}
                [:div {:className "field"}
                 [:label {} "Amount"]
                 [:input {:id "a1" :onChange (fn [e] (swap! values assoc :a1 (-> e :target :value)))}]]
                [:div {:className "field"}
                 [:label {} "Amount"]
                 [:input {:id "a2" :onChange (fn [e] (swap! values assoc :a2 (-> e :target :value)))}]]]]
    (hic/type-text-into-labeled! tree "Amount" "100" 0)
    (hic/type-text-into-labeled! tree "Amount" "200" 1)
    (assertions
      "Types into correct input based on n parameter"
      (:a1 @values) => "100"
      (:a2 @values) => "200")))
