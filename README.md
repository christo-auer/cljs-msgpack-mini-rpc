# Disclaimer

This project is not yet documented, though it should be usable together with
[cljs-msgpack-lite](http://github.com/christo-auer/cljs-msgpack-lite).

# Introduction

`cljs-msgpack-mini-rpc` is an implementation of the
[msgpack-rpc](https://github.com/msgpack-rpc/msgpack-rpc) protocol for
[ClojureScript](https://clojurescript.org/). It fully embraces
[clojure.core.async](https://github.com/clojure/core.async) and implements the
routing of msgpack messages using only the high-level channel multiplexing and
demultiplexing mechanics of `clojure.core.async` (in fact, the core module does
not use `go` at all).

`cljs-msgpack-mini-rpc` can be used in combination with
[cljs-msgpack-lite](https://github.com/christo-auer/cljs-msgpack-lite) for
encoding and decoding msgpack messages to and from, e.g., streams (see
[Examples](#examples)).
