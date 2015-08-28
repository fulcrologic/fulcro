(ns dom-tools.event-sim-spec)





















;(spec "blag"
;      (provided "async stuff does blah"
;                (js/setTimeout (capture f) 200) => (async 200 (f [1 2 3]))
;                (ajax/get (capture url) (capture gc) anything) => (async 150 (gc [4 5 6]))
;
;        (let [state (my-root)                               ; set to 6/11/2014
;              rendering (fn [] (Calendar :cal state))
;              ]
;          (behavior ""
;                    (click-button "Next Month" (rendering)) => anything
;                    (-> @state :cal :month) => 7
;                    (rendering) => (contains-string ".label" "7/1/2014")
;                    clock-ticks => 201
;                    (-> @state :cal :data) => [1 2 3]
;                    clock-ticks => 150
;                    (-> @state :cal :ajax-data) => [4 5 6]
;
;                    )
;
;          )))
