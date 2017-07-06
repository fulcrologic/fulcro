# Contributing

If you'd like to submit a PR, please follow these general guidelines:

- Either talk about it in Slack on #untangled, or open a github issue
- Do development against the *develop* branch (we use git flow). PRs should be directed at the develop branch. Master is
  the latest release, not the live development.
- In general, please squash your change into a single commit
- Add an entry to the CHANGELOG describing the change

## Github Instructions

Basically gollow the instructions here:

https://help.github.com/categories/collaborating-with-issues-and-pull-requests/

## General Guidelines

In general we'd prefer there be tests for code that is added/modified. The
testing style is BDD, where we use untangled-spec to allow for a more
human-readable test-suite.

The intention is to have a specification be named by a sentence
subject clause (e.g. "The thing I'm writing"), and each nested
behavior use language that completes the sentence.

For example:

```clojure
(specification "some-function"
  (behavior "computes the hubledy"
     (assertions
       "for boundary condition A"
       (some-function boundaryA) => 42
       ...
```

which will render as an outline:

- some-function
    - computes the hubledy
         - for boundary condition A

Then when a tests fails it is relatively easy to construct what is going
wrong without having to go read the tests.

## Running Tests

Since you should be writing tests, you probably want to know how to
run them.

### Clojure

There are several hundred tests that need to run via clojure. If you're doing development, then
you can either use a console:

```bash
$ lein test-refresh
```

OR you can try the new browser rendering of spec. This is still a little rough, but nicer overall than
the console. 

```bash 
$ lein run -m clojure.main -e '(server-test-server)'
```

and open [http://localhost:8888/untangled-spec-server-tests.html]().

Before submitting a PR, you should at least run `lein test-refresh :run-once`.

### Clojurescript

The client-side tests must run in a browser. You can start those using the `run-figwheel.sh` script:

```bash 
$ ./run-figwheel.sh test
```

then browse to [http://localhost:8080/test.html]() .

Before submitting a PR, please run `make tests` which will run the client-side
tests as if they were running on a headless CI server.

### Test Selectors (ONLY WHEN RENDERED IN BROWSER)

If you add the keyword `:focused` after the string name of a specification it will mark that
spec as focused. You can use the filters (in the thumb menu of the UI) to disable all tests but the
focused ones. This makes it easier to follow what you're testing/coding in the UI.

```clojure 
(specification "My thing" :focused 
```

Please remember to remove your `:focused` markers before submitting a PR.


