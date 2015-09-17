(ns untangled.spec.i18n.util-spec
  (:require [clojure.test :refer (is deftest run-tests testing do-report)]
            [untangled.i18n.util :as u]
            [smooth-spec.core :refer (specification behavior provided assertions)]
            ))

(def po-file "# SOME DESCRIPTIVE TITLE.\n# Copyright (C) YEAR THE PACKAGE'S COPYRIGHT HOLDER\n# This file is distributed under the same license as the PACKAGE package.\n# FIRST AUTHOR <EMAIL@ADDRESS>, YEAR.\n#\nmsgid \"\"\nmsgstr \"\"\n\"Project-Id-Version: \\n\"\n\"Report-Msgid-Bugs-To: \\n\"\n\"POT-Creation-Date: 2015-09-15 15:24-0700\\n\"\n\"PO-Revision-Date: 2015-09-15 15:30-0700\\n\"\n\"Language-Team: \\n\"\n\"MIME-Version: 1.0\\n\"\n\"Content-Type: text/plain; charset=UTF-8\\n\"\n\"Content-Transfer-Encoding: 8bit\\n\"\n\"X-Generator: Poedit 1.8.4\\n\"\n\"Last-Translator: \\n\"\n\"Plural-Forms: nplurals=2; plural=(n != 1);\\n\"\n\"Language: es_MX\\n\"\n\n#: i18n/survey.js:26344\nmsgid \"A sub-component with local state.\"\nmsgstr \"Un subcomponente de estado local.\"\n\n#: i18n/survey.js:26345\nmsgid \"Change my mood...\"\nmsgstr \"Cambiar mi estado de ánimo…\"\n\n#: i18n/survey.js:26345\nmsgid \"Happy!\"\nmsgstr \"¡Feliz!\"\n\n#: i18n/survey.js:26346\nmsgid \"Sad :(\"\nmsgstr \"Triste :(\"\n\n#: i18n/survey.js:26354\nmsgctxt \"abbreviation for male gender\"\nmsgid \"M\"\nmsgstr \"H\"\n\n#: i18n/survey.js:26355\nmsgid \"A button with a click count: \"\nmsgstr \"Un botón con un clic la cuenta:\"\n\n#: i18n/survey.js:26355\nmsgid \"Click me\"\nmsgstr \"Clic aquí\"\n\n#: i18n/survey.js:26356\nmsgid \"An input that is two-way bound:\"\nmsgstr \"Límite de una entrada que es de dos vía:\"\n\n#: i18n/survey.js:26358\nmsgid \"Sub component below: ({swings, number} mood swings so far)\"\nmsgstr \"Componente de sub abajo: ({columpios, número} hasta el momento de ánimo)\"\n")
(def po-file-with-embedded-newlines "# SOME DESCRIPTIVE TITLE.\n# Copyright (C) YEAR THE PACKAGE'S COPYRIGHT HOLDER\n# This file is distributed under the same license as the PACKAGE package.\n# FIRST AUTHOR <EMAIL@ADDRESS>, YEAR.\n#\nmsgid \"\"\nmsgstr \"\"\n\"Project-Id-Version: \\n\"\n\"Report-Msgid-Bugs-To: \\n\"\n\"POT-Creation-Date: 2015-09-17 09:10-0700\\n\"\n\"PO-Revision-Date: 2015-09-17 09:12-0700\\n\"\n\"Language-Team: \\n\"\n\"MIME-Version: 1.0\\n\"\n\"Content-Type: text/plain; charset=UTF-8\\n\"\n\"Content-Transfer-Encoding: 8bit\\n\"\n\"X-Generator: Poedit 1.8.4\\n\"\n\"Last-Translator: \\n\"\n\"Plural-Forms: nplurals=2; plural=(n > 1);\\n\"\n\"Language: fr_CA\\n\"\n\n#: i18n/survey.js:26344\nmsgid \"A sub-component with local state.\"\nmsgstr \"\"\n\"some translation\\n\"\n\"with multiple lines\\n\"\n\"that I care about\"\n\n#: i18n/survey.js:26345\nmsgid \"Change my mood...\"\nmsgstr \"\"\n\n#: i18n/survey.js:26345\nmsgid \"Happy!\"\nmsgstr \"\"\n\n#: i18n/survey.js:26346\nmsgid \"Sad :(\"\nmsgstr \"\"\n\n#: i18n/survey.js:26354\nmsgid \"here is a NEW STRING to translate!\"\nmsgstr \"\"\n\n#: i18n/survey.js:26355\nmsgctxt \"abbreviation for male gender\"\nmsgid \"M\"\nmsgstr \"\"\n\n#: i18n/survey.js:26355\nmsgid \"A button with a click count: \"\nmsgstr \"\"\n\n#: i18n/survey.js:26356\nmsgid \"Click me\"\nmsgstr \"\"\n\n#: i18n/survey.js:26356\nmsgid \"An input that is two-way bound:\"\nmsgstr \"\"\n\n#: i18n/survey.js:26358\nmsgid \"Sub component below: ({swings, number} mood swings so far)\"\nmsgstr \"\"\n")
(def empty-acc {:seen {:context "" :id ""} :js-obj {}})
(def acc-with-id-and-ctx {:seen {:context "hey" :id "ho"} :js-obj {}})
(def acc-with-id {:seen {:context "" :id "ho"} :js-obj {}})
(def acc-with-ctx {:seen {:context "hey" :id ""} :js-obj {}})
(def msgctxt-line "msgctxt \"abbreviation for male gender\"")
(def msgid-line "msgid \"A button with a click count: \"")
(def msgstr-line "msgstr \"Clic aquí\"")

;(specification "the write-js-translations-file function"
;               (let [trans-map (u/map-po-to-translations "/Users/Dave/projects/survey/i18n/msgs/es_MX.po")
;                     trans-str (u/stringify-translations trans-map)]
;                 (behavior "writes a file"
;                           (assertions
;                             (u/write-js-translation-file "/tmp/es-MX.js" trans-str) => nil)
;                           )))


(specification "the stringify-translations function"
               (behavior "generates valid JSON"
                         (assertions
                           (u/stringify-translations {"fizz" "buzz"}) => "{\"fizz\":\"buzz\"}"))
               (behavior "tolerates being passed an empty map"
                         (assertions
                           (u/stringify-translations {}) => "{}"
                           ))
               (behavior "wraps keys and values in {}"
                         (assertions
                           (re-find #"^\{" (u/stringify-translations {"fizz" "buzz"})) => "{"
                           (re-find #"\}$" (u/stringify-translations {"fizz" "buzz"})) => "}"
                           ))
               )

(specification "the po-to-js function"
               (provided "when given a PO file"
                         (u/get-file f) =3x=> po-file
                         (behavior "maps msgctxt|msgid to translated string"
                                   (assertions
                                     (get (u/map-po-to-translations po-file) "|Sad :(") => "Triste :("
                                     (get (u/map-po-to-translations po-file) "|A button with a click count: ") => "Un botón con un clic la cuenta:"
                                     (get (u/map-po-to-translations po-file) "|Click me") => "Clic aquí"
                                     ))))


(specification "the parse-po function"
               (behavior "(when given a line without msgid, msgctxt, or msgstr)"
                         (behavior "returns acc un-changed"
                                   (assertions
                                     (u/parse-po acc-with-id-and-ctx "nothing to see here") => acc-with-id-and-ctx)))

               (behavior "accumulates intermediate key-building strings"
                         (assertions
                           (:seen (u/parse-po {:seen {:context "", :id "A button with a click count: "}, :js-obj {}}
                                              msgctxt-line)) => {:context "abbreviation for male gender"
                                                                 :id      "A button with a click count: "}

                           (:seen (u/parse-po {:seen {:context "abbreviation for male gender", :id ""}, :js-obj {}}
                                              msgid-line)) => {:context "abbreviation for male gender"
                                                               :id      "A button with a click count: "}))

               (behavior "finds the translation context"
                         (assertions
                           (:seen (u/parse-po empty-acc msgctxt-line)) => {:context "abbreviation for male gender"
                                                                           :id      ""}))

               (behavior "finds the message id"
                         (assertions
                           (:seen (u/parse-po empty-acc msgid-line)) => {:context ""
                                                                         :id      "A button with a click count: "}))
               (behavior "(when passed a line containing a translation string)"
                         (behavior "forgets which msgid and msgctxt it has seen"
                                   (assertions
                                     (:seen (u/parse-po acc-with-id-and-ctx msgstr-line)) => {:context "" :id ""}
                                     ))
                         (behavior "(when translation only has a msgid)"
                                   (behavior "stores translation at '|msgid' key"
                                             (assertions
                                               (:js-obj (u/parse-po acc-with-id msgstr-line)) => {"|ho" "Clic aquí"}
                                               )))
                         (behavior "(when translation has both msgctxt and msgid)"
                                   (behavior "stores translation at 'msgctxt|msgid' key"
                                             (assertions
                                               (:js-obj (u/parse-po acc-with-id-and-ctx msgstr-line)) => {"hey|ho" "Clic aquí"}
                                               )))
                         )

               ; infinite loop????!!??!?!?!
               ;(provided "when passed a line containing a translation string"
               ;          (prn "STARTED PROVIDED")
               ;          (behavior "forgets which msgid and msgctxt it has seen"
               ;                    (assertions
               ;                      (:seen (u/parse-po acc-with-id-and-ctx msgstr-line)) => {:context ""
               ;                                                                               :id      ""}))
               ;          (prn "ENDED PROVIDED")
               ;          )
               )



(run-tests 'untangled.spec.i18n.util-spec)
