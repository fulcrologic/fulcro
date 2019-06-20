tests:
	npm install
	npx shadow-cljs -A:dev compile ci-tests
	npx karma start --single-run
	clojure -A:dev:test:clj-tests -J-Dghostwheel.enabled=true

dev:
	clojure -A:dev:test:clj-tests -J-Dghostwheel.enabled=true --watch --fail-fast --no-capture-output
