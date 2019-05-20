tests:
	npm install
	npx shadow-cljs compile ci-tests
	npx karma start --single-run
	clojure -A:dev:test:clj-tests -J-Dghostwheel.enabled=true

dev:
	clojure -A:dev:provided:test:clj-tests --watch --fail-fast 
