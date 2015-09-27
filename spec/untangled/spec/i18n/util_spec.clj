(ns untangled.spec.i18n.util-spec
  (:require [clojure.test :refer (is deftest run-tests testing do-report)]
            [untangled.i18n.util :as u]
            [smooth-spec.core :refer (specification behavior provided assertions)]
            [smooth-spec.report :as report]
            ))
(def po-file-with-embedded-newlines "# SOME DESCRIPTIVE TITLE.\n# Copyright (C) YEAR THE PACKAGE'S COPYRIGHT HOLDER\n# This file is) distributed under the same license as the PACKAGE package.\n# FIRST AUTHOR <EMAIL@ADDRESS>, YEAR.\n#\n#, fuzzy\nmsgid \"\"\nmsgstr \"\"\n\"Project-Id-Version: PACKAGE VERSION\\n\"\n\"Report-Msgid-Bugs-To: \\n\"\n\"POT-Creation-Date: 2015-09-24 14:28-0700\\n\"\n\"PO-Revision-Date: YEAR-MO-DA HO:MI+ZONE\\n\"\n\"Last-Translator: FULL NAME <EMAIL@ADDRESS>\\n\"\n\"Language-Team: LANGUAGE <LL@li.org>\\n\"\n\"Language: \\n\"\n\"MIME-Version: 1.0\\n\"\n\"Content-Type: text/plain; charset=CHARSET\\n\"\n\"Content-Transfer-Encoding: 8bit\\n\"\n\n#: i18n/out/compiled.js:26732\nmsgctxt \"context for a multiline xlation\"\nmsgid \"\"\n\"line one\\n\"\n\"two\\n\"\n\"three\"\nmsgstr \"\"\n\"lina uno\\n\"\n\"dos\\n\"\n\"tres\"\n\n#: i18n/out/compiled.js:26732\nmsgid \"\"\n\"Select a language\\n\"\n\" to use\\n\"\n\"maybe\"\nmsgstr \"\"\n\"some xlated line\\n\"\n\" por uso\\n\"\n\"que?\"\n")
(def malformed-po-file "# SOME DESCRIPTIVE TITLE.\n# Copyright (C) YEAR THE PACKAGE'S COPYRIGHT HOLDER\n# This file is) distributed under the same license as the PACKAGE package.\n# FIRST AUTHOR <EMAIL@ADDRESS>, YEAR.\n#\n#, fuzzy\nmsgid \"\"\nmsgstr \"\"\n\"Project-Id-Version: PACKAGE VERSION\\n\"\n\"Report-Msgid-Bugs-To: \\n\"\n\"POT-Creation-Date: 2015-09-24 14:28-0700\\n\"\n\"PO-Revision-Date: YEAR-MO-DA HO:MI+ZONE\\n\"\n\"Last-Translator: FULL NAME <EMAIL@ADDRESS>\\n\"\n\"Language-Team: LANGUAGE <LL@li.org>\\n\"\n\"Language: \\n\"\n\"MIME-Version: 1.0\\n\"\n\"Content-Type: text/plain; charset=CHARSET\\n\"\n\"Content-Transfer-Encoding: 8bit\\n\"\n#: i18n/out/compiled.js:26732\nmsgctxt \"context for a multi~ xlation\"\nmsgid \"\"\n\"line one\\n\"\n\"two\\n\"\n\"three\"\nmsgstr \"\"\n\"lina uno\\n\"\n\"dos\\n\"\n\"tres\"\n#: i18n/out/compiled.js:26732\nmsgid \"\"\n\"Select a language\\n\"\n\" to use\\n\"\n\"maybe\"\nmsgstr \"\"\n\"some xlated line\\n\"\n\" por uso\\n\"\n\"que?\"\n")
(def po-file "# SOME DESCRIPTIVE TITLE.\n# Copyright (C) YEAR THE PACKAGE'S COPYRIGHT HOLDER\n# This file is distributed under the same license as the PACKAGE package.\n# FIRST AUTHOR <EMAIL@ADDRESS>, YEAR.\n#\nmsgid \"\"\nmsgstr \"\"\n\"Project-Id-Version: \\n\"\n\"Report-Msgid-Bugs-To: \\n\"\n\"POT-Creation-Date: 2015-09-15 15:24-0700\\n\"\n\"PO-Revision-Date: 2015-09-15 15:30-0700\\n\"\n\"Language-Team: \\n\"\n\"MIME-Version: 1.0\\n\"\n\"Content-Type: text/plain; charset=UTF-8\\n\"\n\"Content-Transfer-Encoding: 8bit\\n\"\n\"X-Generator: Poedit 1.8.4\\n\"\n\"Last-Translator: \\n\"\n\"Plural-Forms: nplurals=2; plural=(n != 1);\\n\"\n\"Language: es_MX\\n\"\n\n#: i18n/survey.js:26344\nmsgid \"A sub-component with local state.\"\nmsgstr \"Un subcomponente de estado local.\"\n\n#: i18n/survey.js:26345\nmsgid \"Change my mood...\"\nmsgstr \"Cambiar mi estado de ánimo…\"\n\n#: i18n/survey.js:26345\nmsgid \"Happy!\"\nmsgstr \"¡Feliz!\"\n\n#: i18n/survey.js:26346\nmsgid \"Sad :(\"\nmsgstr \"Triste :(\"\n\n#: i18n/survey.js:26354\nmsgctxt \"abbreviation for male gender\"\nmsgid \"M\"\nmsgstr \"H\"\n\n#: i18n/survey.js:26355\nmsgid \"A button with a click count: \"\nmsgstr \"Un botón con un clic la cuenta:\"\n\n#: i18n/survey.js:26355\nmsgid \"Click me\"\nmsgstr \"Clic aquí\"\n\n#: i18n/survey.js:26356\nmsgid \"An input that is two-way bound:\"\nmsgstr \"Límite de una entrada que es de dos vía:\"\n\n#: i18n/survey.js:26358\nmsgid \"Sub component below: ({swings, number} mood swings so far)\"\nmsgstr \"Componente de sub abajo: ({columpios, número} hasta el momento de ánimo)\"\n")

(specification "the map-translations funtion"
               (provided "when a translation contains single-line translations"
                         (slurp some-file) =1x=> po-file
                         (let [translations (u/map-translations "wat")
                               xlation-with-ctxt (get translations "abbreviation for male gender|M")
                               xlation-without-ctxt (get translations "|A sub-component with local state.")]
                           (clojure.pprint/pprint translations)
                           (behavior "stores the translation without context"
                                     (assertions
                                       xlation-without-ctxt => "Un subcomponente de estado local."))
                           (behavior "stores the translation with context"
                                     (assertions
                                       xlation-with-ctxt => "H"))))
               (provided "when a translation contains embedded newlines"
                         (slurp some-file) =1x=> po-file-with-embedded-newlines
                         (let [translations (u/map-translations "wat")
                               mutliline-xlation (get translations "|Select a language\n to use\nmaybe")]
                           (behavior "stores multiline values"
                                     (assertions
                                       mutliline-xlation => "some xlated line\n por uso\nque?"))
                           (behavior "stores values at multiline keys"
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
                             (u/join-quoted-strings strings) => "line one\ntwo\nthree"))))

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
                             (:msgstr mapped-translation) => "lina uno\ndos\ntres"))))

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
                                     (count (first (u/group-translations "some fname"))) => 3))))


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
                               grouped-without-ctxt (u/group-chunks multiln-without-ctxt)]
                           (behavior "removes extra \\ escape from embedded newlines"
                                     (assertions
                                       (-> grouped-without-ctxt first second) => "\"line one\n\""))
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

; this made test-refresh work
;(report/with-smooth-output (run-tests 'untangled.spec.i18n.util-spec))
