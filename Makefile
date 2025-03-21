tests:
	yarn
	npx shadow-cljs -A:dev compile ci-tests
	npx karma start --single-run
	clojure -A:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled

dev:
	clojure -A:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled --watch --fail-fast --no-capture-output

workspaces:
	echo "Workspaces will be on: http://localhost:9002"
	npx shadow-cljs -A:dev:workspaces watch workspaces

deploy:
	rm -rf target
	mvn deploy
