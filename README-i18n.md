# Internationalization

See the developer's guide for more detail. This file is a quick
reference:

## Extracting Strings (First Time)

- Install GNU gettext (e.g. with `brew`). Make sure xgettext and msgmerge are on the env PATH that your REPL will see. For example,
in IntelliJ `/usr/bin` or `/usr/local/bin` (it doesn't load your .profile).
- Add a cljs build to your project with `:whitespace` optimizations
- Build that CLJS build
- Start a CLJ REPL and `(require 'fulcro.gettext)`

```
fulcro.gettext=> (extract-strings {:po "i18n" :js-path "resources/private/js/i18n.js"})
```

this should create an `i18n` folder containing a `messages.pot`. This is
your translation template.

## Creating a new Locale

Use `msginit` from a command line:

```
$ cd i18n
$ lang=es
$ msginit --no-translator -l $lang --no-wrap -o ${lang}.po -i messages.pot
```

This will create a new `.po` file. Send this to your translator.

## Translate

I recommend PoEdit. It is a GUI tool that allows you to create new translation locales and fill them in. The
pro version will even look up translations on Google Translate so you can just touch them up.

## Deploy Translations

In order for your translations to appear in your app you have to convert them to CLJC files.

- Start a CLJ REPL

```
user=> `(require 'fulcro.gettext)`
user=> (fulcro.gettext/deploy-translations {:src "src/main" :po "i18n" :ns "some-app.my-translations"})
```

Where `:src` is your application's main source folder, `:po` is where you po files are located, and `:ns` is a
*string* version of the namespace you want the translations to appear in.

## Update Translations

Just repeat the `extract-strings` function. It will automatically call `msgmerge` on all
existing locales to both keep your old translations, and put in the new ones. Also, of course,
repeat the translation and deployment steps.

## Use Translations

In order to use the translations, you have two options:

1. Require all of you locales in your client main (which will load them), then just use `change-locale`.
2. Configure each locale as a module of your program (code-splitting). Any non-loaded locale will then
be autoloaded when you use `change-locale`. (NOT WORKING YET. Waiting on July 2017 release of better
module support).

## Server-side Rendering

The UI will render whatever the current locale is set to. This is a global dynamic variable holding an atom that can
be set on the server (TODO: wrap a wrapper for this that can error check it):

```
(reset! fulcro.i18n/*current-locale* :es)
(dom/render-to-str (ui-root props))
```

