# 0.2.1
- `state-callback`, a function of arity 2, can now be passed to the network. This can be used to execute actions based on the websockets state change.
- Reconnecting is now possible through the `ChannelSocket` protocol's `reconnect` function.
- Add validation for ws connection origins.
- Add validation for client ids upon http GET request. This is a place for potential XSS.

# 0.2.0
- `global-error-callback` is now a arity 2 function. It takes status and body, in that order. Refer to github issue [untangled-web/untangled-client#14](https://github.com/untangled-web/untangled-client/issues/14).
- Uses untangled-server 0.5.1 and untangled-client 0.5.0.

# 0.1.0
- Initial release
