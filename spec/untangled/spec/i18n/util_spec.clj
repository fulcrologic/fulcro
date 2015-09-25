(ns untangled.spec.i18n.util-spec
  (:require [clojure.test :refer (is deftest run-tests testing do-report)]
            [untangled.i18n.util :as u]
            [smooth-spec.core :refer (specification behavior provided assertions)]
            [smooth-spec.report :as report]
            ))
(def po-file-with-embedded-newlines "# SOME DESCRIPTIVE TITLE.\n# Copyright (C) YEAR THE PACKAGE'S COPYRIGHT HOLDER\n# This file is) distributed under the same license as the PACKAGE package.\n# FIRST AUTHOR <EMAIL@ADDRESS>, YEAR.\n#\n#, fuzzy\nmsgid \"\"\nmsgstr \"\"\n\"Project-Id-Version: PACKAGE VERSION\\n\"\n\"Report-Msgid-Bugs-To: \\n\"\n\"POT-Creation-Date: 2015-09-24 14:28-0700\\n\"\n\"PO-Revision-Date: YEAR-MO-DA HO:MI+ZONE\\n\"\n\"Last-Translator: FULL NAME <EMAIL@ADDRESS>\\n\"\n\"Language-Team: LANGUAGE <LL@li.org>\\n\"\n\"Language: \\n\"\n\"MIME-Version: 1.0\\n\"\n\"Content-Type: text/plain; charset=CHARSET\\n\"\n\"Content-Transfer-Encoding: 8bit\\n\"\n\n#: i18n/out/compiled.js:26732\nmsgctxt \"context for a multiline xlation\"\nmsgid \"\"\n\"line one\\n\"\n\"two\\n\"\n\"three\"\nmsgstr \"\"\n\"lina uno\\n\"\n\"dos\\n\"\n\"tres\"\n\n#: i18n/out/compiled.js:26732\nmsgid \"\"\n\"Select a language\\n\"\n\" to use\\n\"\n\"maybe\"\nmsgstr \"\"\n\"some xlated line\\n\"\n\" por uso\\n\"\n\"que?\"\n")
(def malformed-po-file "# SOME DESCRIPTIVE TITLE.\n# Copyright (C) YEAR THE PACKAGE'S COPYRIGHT HOLDER\n# This file is) distributed under the same license as the PACKAGE package.\n# FIRST AUTHOR <EMAIL@ADDRESS>, YEAR.\n#\n#, fuzzy\nmsgid \"\"\nmsgstr \"\"\n\"Project-Id-Version: PACKAGE VERSION\\n\"\n\"Report-Msgid-Bugs-To: \\n\"\n\"POT-Creation-Date: 2015-09-24 14:28-0700\\n\"\n\"PO-Revision-Date: YEAR-MO-DA HO:MI+ZONE\\n\"\n\"Last-Translator: FULL NAME <EMAIL@ADDRESS>\\n\"\n\"Language-Team: LANGUAGE <LL@li.org>\\n\"\n\"Language: \\n\"\n\"MIME-Version: 1.0\\n\"\n\"Content-Type: text/plain; charset=CHARSET\\n\"\n\"Content-Transfer-Encoding: 8bit\\n\"\n#: i18n/out/compiled.js:26732\nmsgctxt \"context for a multi~ xlation\"\nmsgid \"\"\n\"line one\\n\"\n\"two\\n\"\n\"three\"\nmsgstr \"\"\n\"lina uno\\n\"\n\"dos\\n\"\n\"tres\"\n#: i18n/out/compiled.js:26732\nmsgid \"\"\n\"Select a language\\n\"\n\" to use\\n\"\n\"maybe\"\nmsgstr \"\"\n\"some xlated line\\n\"\n\" por uso\\n\"\n\"que?\"\n")
(def po-file "# SOME DESCRIPTIVE TITLE.\n# Copyright (C) YEAR THE PACKAGE'S COPYRIGHT HOLDER\n# This file is distributed under the same license as the PACKAGE package.\n# FIRST AUTHOR <EMAIL@ADDRESS>, YEAR.\n#\nmsgid \"\"\nmsgstr \"\"\n\"Project-Id-Version: \\n\"\n\"Report-Msgid-Bugs-To: \\n\"\n\"POT-Creation-Date: 2015-09-15 15:24-0700\\n\"\n\"PO-Revision-Date: 2015-09-15 15:30-0700\\n\"\n\"Language-Team: \\n\"\n\"MIME-Version: 1.0\\n\"\n\"Content-Type: text/plain; charset=UTF-8\\n\"\n\"Content-Transfer-Encoding: 8bit\\n\"\n\"X-Generator: Poedit 1.8.4\\n\"\n\"Last-Translator: \\n\"\n\"Plural-Forms: nplurals=2; plural=(n != 1);\\n\"\n\"Language: es_MX\\n\"\n\n#: i18n/survey.js:26344\nmsgid \"A sub-component with local state.\"\nmsgstr \"Un subcomponente de estado local.\"\n\n#: i18n/survey.js:26345\nmsgid \"Change my mood...\"\nmsgstr \"Cambiar mi estado de ánimo…\"\n\n#: i18n/survey.js:26345\nmsgid \"Happy!\"\nmsgstr \"¡Feliz!\"\n\n#: i18n/survey.js:26346\nmsgid \"Sad :(\"\nmsgstr \"Triste :(\"\n\n#: i18n/survey.js:26354\nmsgctxt \"abbreviation for male gender\"\nmsgid \"M\"\nmsgstr \"H\"\n\n#: i18n/survey.js:26355\nmsgid \"A button with a click count: \"\nmsgstr \"Un botón con un clic la cuenta:\"\n\n#: i18n/survey.js:26355\nmsgid \"Click me\"\nmsgstr \"Clic aquí\"\n\n#: i18n/survey.js:26356\nmsgid \"An input that is two-way bound:\"\nmsgstr \"Límite de una entrada que es de dos vía:\"\n\n#: i18n/survey.js:26358\nmsgid \"Sub component below: ({swings, number} mood swings so far)\"\nmsgstr \"Componente de sub abajo: ({columpios, número} hasta el momento de ánimo)\"\n")
(def empty-acc {:seen {:context "" :id ""} :cljs-obj {}})
(def acc-with-id-and-ctx {:seen {:context "hey" :id "ho"} :cljs-obj {}})
(def acc-with-id {:seen {:context "" :id "ho"} :cljs-obj {}})
(def acc-with-ctx {:seen {:context "hey" :id ""} :cljs-obj {}})
(def msgctxt-line "msgctxt \"abbreviation for male gender\"")
(def msgid-line "msgid \"A button with a click count: \"")
(def msgstr-line "msgstr \"Clic aquí\"")

(specification "the map-translations funtion"
               (provided "when a translation contains embedded newlines"
                         (slurp some-file) =1x=> po-file-with-embedded-newlines
                         (behavior "stores values at multiline keys"
                                   (let [translations (u/map-translations "wat")]
                                     (assertions
                                       (contains? translations "|Select a language\n to use\nmaybe") => true)))))

(specification "the join-quoted-strings function"
               (let [string ["msgctxt \"context for a multiline xlation\""]
                     strings ["msgid \"\"" "\"line one\n\"" "\"two\n\"" "\"three\""]]
                 (behavior "returns unquoted string from single string"
                           (assertions
                             (u/join-quoted-strings string) => "context for a multiline xlation"))
                 (behavior "returns unquoted string from a vector of quoted strings"
                           (assertions
                             (u/join-quoted-strings strings) => "line one\ntwo\nthree"))
                 ))

(specification "the inline-strings function"
               (let [grouped-chunk [["msgctxt \"context for a multiline xlation\""]
                                    ["msgid \"\"" "\"line one\n\"" "\"two\n\"" "\"three\""]
                                    ["msgstr \"\"" "\"lina uno\n\"" "\"dos\n\"" "\"tres\""]]
                     mapped-translation (u/inline-strings {} grouped-chunk)]
                 (behavior "keys content by :msgid, :msgctxt and :msgstr"
                           (assertions
                             (contains? mapped-translation :msgid) => true
                             (contains? mapped-translation :msgctxt) => true
                             (contains? mapped-translation :msgstr) => true))
                 (behavior "collapses multiline subcomponent into a single value"
                           (assertions
                             (:msgctxt mapped-translation) => "context for a multiline xlation"
                             (:msgid mapped-translation) => "line one\ntwo\nthree"
                             (:msgstr mapped-translation) => "lina uno\ndos\ntres"))
                 ))

(specification "the group-translations function"
               (provided "when grouping translations"
                         (slurp some-file) =2x=> po-file

                         (behavior "begins groups with msgid or msgctxt"
                                   (assertions
                                     (-> "some fname" u/group-translations (nth 4) first first (subs 0 7)) => "msgctxt"
                                     (-> "some fname" u/group-translations first first first (subs 0 5)) => "msgid")))

               (provided "when given a malformed po file string"
                         (slurp some-file) =1x=> malformed-po-file

                         (behavior "returns nil"
                                   (assertions
                                     (u/group-translations "some fname") => nil)))

               (provided "when given a multi-line translation"
                         (slurp some-file) =1x=> po-file-with-embedded-newlines

                         (behavior "returns groups of translations"
                                   (assertions
                                     (first (u/group-translations "some fname")) =>
                                     [["msgctxt \"context for a multiline xlation\""]
                                      ["msgid \"\"" "\"line one\\n\"" "\"two\\n\"" "\"three\""]
                                      ["msgstr \"\"" "\"lina uno\\n\"" "\"dos\\n\"" "\"tres\""]]))))


(specification "the group-chunks function"
               (behavior "when given an ungrouped translation chunk"
                         (let [multiln-with-ctxt '("msgctxt \"context for a multiline xlation\""
                                                    "msgid \"\""
                                                    "\"line one\\n\""
                                                    "\"two\\n\""
                                                    "\"three\""
                                                    "msgstr \"\""
                                                    "\"lina uno\\n\""
                                                    "\"dos\\n\""
                                                    "\"tres\"")
                               multiln-without-ctxt '("msgid \"\""
                                                       "\"line one\\n\""
                                                       "\"two\\n\""
                                                       "\"three\""
                                                       "msgstr \"\""
                                                       "\"lina uno\\n\""
                                                       "\"dos\\n\""
                                                       "\"tres\"")
                               grouped-with-ctxt (u/group-chunks multiln-with-ctxt)
                               grouped-without-ctxt (u/group-chunks multiln-without-ctxt)
                               ]
                           (behavior "ends groups with msgstr"
                                     (assertions
                                       (-> grouped-without-ctxt reverse first first (subs 0 6)) => "msgstr"
                                       (-> grouped-with-ctxt reverse first first (subs 0 6)) => "msgstr"))
                           (behavior "begins groups with msgid or msgctxt"
                                     (assertions
                                       (-> grouped-without-ctxt first first (subs 0 5)) => "msgid"
                                       (-> grouped-with-ctxt first first (subs 0 7)) => "msgctxt")))))


(specification "the wrap-with-swap function"
               (let [code-string (u/wrap-with-swap :locale "fr-CA" :translation "{\"fizz\" \"buzz\"}")
                     re #"(?ms)^(\(ns untangled.translations.fr-CA).*"
                     match (last (re-matches re code-string))]
                 (behavior "emits code string that begins with a namespace delcaration"
                           (assertions
                             match => "(ns untangled.translations.fr-CA")))

               (let [code-string (u/wrap-with-swap :locale "fr-CA" :translation "{\"fizz\" \"buzz\"}")
                     re #"(?ms)^.*(untangled.i18n.core/\*loaded-translations\*).*"
                     match (last (re-matches re code-string))]

                 (behavior "emits code string with default :atom-name"
                           (assertions
                             match => "untangled.i18n.core/*loaded-translations*"))))


(specification "the map-po-to-translations function"
               (provided "when given a PO file"
                         (slurp f) =3x=> po-file
                         (behavior "maps msgctxt|msgid to translated string"
                                   (assertions
                                     (get (u/map-po-to-translations po-file) "|Sad :(") => "Triste :("
                                     (get (u/map-po-to-translations po-file) "|A button with a click count: ") => "Un botón con un clic la cuenta:"
                                     (get (u/map-po-to-translations po-file) "|Click me") => "Clic aquí"
                                     ))))

(specification "the parse-po function"
               (behavior "(when a translation contains embedded newlines)"
                         (behavior "multiple lines are concatentated together"
                                   (assertions
                                     (u/parse-po acc-with-id-and-ctx "nothing to see here") => acc-with-id-and-ctx)))
               (behavior "(when given a line without msgid, msgctxt, or msgstr)"
                         (behavior "returns acc un-changed"
                                   (assertions
                                     (u/parse-po acc-with-id-and-ctx "nothing to see here") => acc-with-id-and-ctx)))

               (behavior "accumulates intermediate key-building strings"
                         (assertions
                           (:seen (u/parse-po {:seen {:context "", :id "A button with a click count: "}, :cljs-obj {}}
                                              msgctxt-line)) => {:context "abbreviation for male gender"
                                                                 :id      "A button with a click count: "}

                           (:seen (u/parse-po {:seen {:context "abbreviation for male gender", :id ""}, :cljs-obj {}}
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
                                               (:cljs-obj (u/parse-po acc-with-id msgstr-line)) => {"|ho" "Clic aquí"}
                                               )))
                         (behavior "(when translation has both msgctxt and msgid)"
                                   (behavior "stores translation at 'msgctxt|msgid' key"
                                             (assertions
                                               (:cljs-obj (u/parse-po acc-with-id-and-ctx msgstr-line)) => {"hey|ho" "Clic aquí"}
                                               ))))

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


; this made test-refresh wor
; k
(report/with-smooth-output (run-tests 'untangled.spec.i18n.util-spec))

