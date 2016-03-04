tests:
	npm install
	lein doo chrome automated-tests once

test-server:
	rlwrap lein test-refresh
