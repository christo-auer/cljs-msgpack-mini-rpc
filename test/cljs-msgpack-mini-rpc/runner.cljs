(ns cljs-msgpack-mini-rpc.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [cljs-msgpack-mini-rpc.core-test]
              [cljs-msgpack-mini-rpc.helpers-test]))

(doo-tests 'cljs-msgpack-mini-rpc.core-test 
           'cljs-msgpack-mini-rpc.helpers-test)
