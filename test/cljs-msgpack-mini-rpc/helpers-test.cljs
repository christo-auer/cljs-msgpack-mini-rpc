(ns cljs-msgpack-mini-rpc.helpers-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [cljs-msgpack-mini-rpc.core :as core]
            [cljs-msgpack-mini-rpc.helpers :as h]
            [cljs.core.async :refer [chan >! <! pipe close! alt! timeout]]))

(deftest request!-test
  (async done
         (let [[raw-in raw-out] [(chan) (chan)]
               attachment (core/attach raw-in raw-out)
               add-ch (chan)]
           (pipe raw-out raw-in)
           (is (= true (core/subscribe-to-request! :add add-ch)))
           (go-loop []
             (when-let [[{out :out} {args :args :as request}] (<! add-ch)]
               (if (-> args count (> 0))
                 (>! out (core/response request :result (apply + args)))
                 (>! out (core/response request :error :not-enough-numbers)))
               (recur))
             (core/unsubscribe-from-request! :add add-ch)
             (done))
           (go
             (is (= [nil 3] (<! (h/request! attachment :add 1 2))))
             (is (= [:not-enough-numbers nil] (<! (h/request! attachment :add))))
             (core/detach attachment)
             (close! raw-in)
             (close! raw-out)
             (close! add-ch)))))

(deftest notify!-test
  (async done
         (let [[raw-in raw-out] [(chan) (chan)]
               attachment (core/attach raw-in raw-out)
               test-atom (atom nil)
               notification-chan (chan)]
           (pipe raw-out raw-in)
           (core/subscribe-to-notification! :set-test-atom notification-chan)
           (let [handler-loop-ch
                 (go []
                     (when-let [[_ {args :args}] (<! notification-chan)]
                       (reset! test-atom (first args)))
                     (core/unsubscribe-from-notification! :set-test-atom notification-chan))]
             (go
               (is (= true (<! (h/notify! attachment :set-test-atom 1))))
               (is (= :finished (alt! (timeout 1000) :timeout handler-loop-ch :finished))) 
               (is (= 1 @test-atom))
               (core/detach attachment)
               (close! raw-in)
               (close! raw-out)
               (close! notification-chan)
               (done))))))


(deftest register-request-handler!-test
  (let [[raw-in raw-out] [(chan) (chan)]
        attachment (core/attach raw-in raw-out)
        add-ch (chan)
        handler-fn (fn [& args]
                     (if (-> args count (> 0))
                       [:result (apply + args)]
                       [:error :not-enough-numbers]))
        handler-loop-ch (h/register-request-handler! :add add-ch handler-fn)]
    (pipe raw-out raw-in)
    (is (some? handler-loop-ch))
    (is (= add-ch (:add (core/get-request-subscriptions))))
    (async done
           (go
             (is (= [nil 3] (<! (h/request! attachment :add 1 2))))
             (is (= [:not-enough-numbers nil] (<! (h/request! attachment :add))))
             (core/detach attachment)
             (close! add-ch)
             (let [timeout (timeout 1000)]
               (is (= :finished (alt! handler-loop-ch :finished timeout :timeout))))
             (is (= nil (:add (core/get-request-subscriptions))))
             (done)))))

(deftest register-notification-handler!-test
  (let [[raw-in raw-out] [(chan) (chan)]
        attachment (core/attach raw-in raw-out)
        notification-chan (chan)
        test-atom (atom nil)
        handler-fn (fn [atom-val]
                     (reset! test-atom atom-val))
        handler-loop-ch (h/register-notification-handler! :set-test-atom notification-chan handler-fn)]
    (pipe raw-out raw-in)
    (is (some? handler-loop-ch))
    (is (= notification-chan (:set-test-atom (core/get-notification-subscriptions))))
    (async done
           (go
             (is (= true (<! (h/notify! attachment :set-test-atom 1))))
             (<! (timeout 0)) ; we have to yield to let notify! actually send the notification
             (close! notification-chan)
             (let [timeout (timeout 1000)]
               (is (= :finished (alt! handler-loop-ch :finished timeout :timeout))))
             (is (= 1 @test-atom))
             (is (= nil (:set-test-atom (core/get-notification-subscriptions))))
             (core/detach attachment)
             (done)))))
