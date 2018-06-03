(defproject cljs-msgpack-mini-rpc "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.908"]
                 [org.clojure/core.async "0.4.474"]
                 [cljs-msgpack-lite "0.1.5"]
                 [org.clojure/test.check "0.10.0-alpha2"]
                 [cljs-node-io "0.5.0"]]

  :plugins [[lein-cljsbuild "1.1.7" :exclusions [[org.clojure/clojure]]]
            [lein-figwheel "0.5.13"]
            [lein-doo "0.1.8"]
            [lein-npm "0.6.2"] 
            [cljfmt "0.5.7"]]

  :npm {:dependencies [[msgpack-lite "0.1.26"]]
        :devDependencies [[ws "3.3.2"]]}

  :source-paths ["src" "test" "example"]

  :clean-targets ["server.js"
                  "target"]

  :cljsbuild {
    :builds [{:id "dev"
              :source-paths ["src"]
              :figwheel true
              :compiler {
                :main cljs-msgpack-mini-rpc.core
                :asset-path "target/js/compiled/dev"
                :output-to "target/js/compiled/cljs_msgpack_mini_rpc.js"
                :output-dir "target/js/compiled/dev"
                :target :nodejs
                :optimizations :none
                :source-map-timestamp true}}
             {:id "example"
              :source-paths ["example"]
              :figwheel true
              :compiler {:main cljs-msgpack-mini-rpc.example
                         :asset-path "target/js/compiled/example"
                         :output-to "target/js/compiled/cljs_msgpack_mini_rpc_example.js"
                         :output-dir "target/js/compiled/example"
                         :target :nodejs
                         :optimizations :none
                         :source-map-timestamp true}}
             {:id "test"
              :source-paths ["src" "test"]
              :compiler {
                :main cljs-msgpack-mini-rpc.runner
                :output-to "target/js/compiled/cljs_msgpack_mini_rpc_test.js"
                :target :nodejs
                :optimizations :none}}
             {:id "prod"
              :source-paths ["src"]
              :compiler {
                :output-to "server.js"
                :output-dir "target/js/compiled/prod"
                :target :nodejs
                :optimizations :simple}}]}

  :profiles {:dev {:dependencies [[figwheel-sidecar "0.5.13"]
                                  [com.cemerick/piggieback "0.2.2"]]
                   :source-paths ["src" "dev" "example"]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}})
