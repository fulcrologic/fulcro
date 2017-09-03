(ns fulcro.gettext-spec
  (:require [clojure.java.io :as io]
            [fulcro.gettext :as gettext]
            [clojure.string :as str]
            [fulcro-spec.core :refer [specification assertions]]))

(specification "Stripping comments"
  (assertions
    "can strip a comment in various scenarios"
    (gettext/strip-comments "# abc\n") => ""
    (gettext/strip-comments "# abc\r") => ""
    (gettext/strip-comments "# abc\r\n# Comment 2\n") => ""
    (gettext/strip-comments "# abc\r\n# Comment 2") => ""
    (gettext/strip-comments "xyz \"#\" abc\n") => "xyz \"#\" abc\n"
    (gettext/strip-comments "#a \nb\n# c\nd") => "b\nd"
    (gettext/strip-comments "#a \nb\n# c\nd\n") => "b\nd\n"
    (gettext/strip-comments "Hi\n# abc\r\n") => "Hi\n"
    (gettext/strip-comments "Hi\n# abc\r\n\nThis is a test") => "Hi\n\nThis is a test"
    (gettext/strip-comments "Hi\n# abc\r\n\nThis is a test\n# and a comment") => "Hi\n\nThis is a test\n"))

(specification "Header detection"
  (assertions
    "Detects translation blocks that look like po headers"
    (map gettext/is-header? (gettext/get-blocks (io/resource "resources/test.po"))) => [false false false]))

(specification "block->translation"
  (let [translations (map gettext/block->translation (gettext/get-blocks (io/resource "resources/test.po")))]
    (assertions
      translations => [{:msgid "Hello" :msgstr "" }
                       {:msgctxt "Abbreviation for Monday" :msgid "M" :msgstr ""}
                       {:msgid "{n,plural,=0 {none} =1 {one} other {#}}\\n\\n      and some\\n      \\\" embedded weirdness \\n"
                        :msgstr ""}])))

