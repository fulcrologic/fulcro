tests:
	pnpm install --frozen-lockfile
	pnpm exec shadow-cljs -A:dev compile ci-tests
	pnpm exec karma start --single-run
	clojure -A:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled -J-Dcom.fulcrologic.fulcro.inspect=true

dev:
	clojure -A:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled -J-Dcom.fulcrologic.fulcro.inspect=true --watch --fail-fast --no-capture-output

deploy:
	rm -rf target
	mvn deploy

check-clj-doc:
	clojure -T:build jar
	clojure -T:check-clj-doc analyze-local