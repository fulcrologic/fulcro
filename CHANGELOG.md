0.4.5
-----
- Renamed react-key to ui/react-key
- Renamed app/locale to ui/locale
- Renamed mutation app/change-locale to ui/change-locale
- Implemented a number of missing things in i18n
- Removed a number of i18n helpers that were redundant to trf
- Added :request-transform networking hook with spec
- Modified load callback to a mutation symbol
- Changed :params of loaders to allow you to specify which prop on the query gets the stated parameters
- Added history method to application protocol, to make implementing history viewer in an app trivial

0.4.6
-----
- Fixed local read bug, and turned on path optimization
- Fixed fallback handling of failed remote transactions, and added lots of tests

0.4.7
-----
- untangled.openid-client/setup, parses any openid claims from the webtoken in the url's hash fragments
