(ns cljs-msgpack-mini-rpc.core-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [cljs-msgpack-mini-rpc.core :as c]
            [cljs.core.async :refer [chan >! <! pipe close!]]))

(deftest ->Request-test
  (is (thrown? js/Error (c/->Request 1 1 1)))
  (is (thrown? js/Error (c/->Request 1 :add 1)))
  (is (thrown? js/Error (c/->Request -1 :add [])))
  (is (thrown? js/Error (c/->Request 1 "" []))))

(deftest ->Notification-test
  (is (thrown? js/Error (c/->Notification 1 1)))
  (is (thrown? js/Error (c/->Notification :add 1)))
  (is (thrown? js/Error (c/->Notification "" []))))

(deftest ->Response-test
  (is (thrown? js/Error (c/->Response -1 nil 1))))

(deftest msp-rpc-array->message-test
  ; Request
  (is (= (c/msp-rpc-array->message [0 1 "add" [1 2]]) (c/->Request 1 :add [1 2]))) ; perfectly ok request
  (is (= [:request :add] (c/topic (c/->Request 1 :add [1 2])))) ; c/topic
  (is (thrown? js/Error (c/msp-rpc-array->message [0 1 "add" nil]))) ; invalid args
  (is (thrown? js/Error (c/msp-rpc-array->message [0 -1 "add"  [1 2]]))) ; invalid msg-id
  (is (thrown? js/Error (c/msp-rpc-array->message [0 1 "" [1 2]]))) ; empty method name
  (is (thrown? js/Error (c/msp-rpc-array->message [0 1 nil [1 2]]))) ; nil method name
  (is (thrown? js/Error (c/msp-rpc-array->message [0 1 "some error" nil]))) ; looks like a c/response with error
  (is (thrown? js/Error (c/msp-rpc-array->message [0 1 nil "some result"]))) ; looks like a c/response with result
  (is (thrown? js/Error (c/msp-rpc-array->message [0 :add [1 2]]))) ; looks like a notification

  ; Response
  (is (= (c/msp-rpc-array->message [1 1 nil "some result"]) (c/->Response 1 nil "some result"))) ; c/response with result
  (is (= (c/msp-rpc-array->message [1 1 "some error" nil]) (c/->Response 1 "some error" nil))) ; c/response with error
  (is (= (c/msp-rpc-array->message [1 1 nil nil]) (c/->Response 1 nil nil))) ; c/response without result and error
  (is (= [:response 1] (c/topic (c/->Response 1 nil [1 2])))) ; c/topic
  (is (thrown? js/Error (c/msp-rpc-array->message [1 1 "some error" "some result"]))) ; c/response with both error and result
  (is (thrown? js/Error (c/msp-rpc-array->message [1 -1 nil "some result"]))) ; invalid msg-id
  (is (thrown? js/Error (c/msp-rpc-array->message [1 :add 1 [1 2]]))) ; looks like request
  (is (thrown? js/Error (c/msp-rpc-array->message [1 :add [1 2]]))) ; looks like notification

  ; Notification
  (is (= (c/msp-rpc-array->message [2 "add" [1 2]]) (c/->Notification :add [1 2]))) ; perfectl ok notification
  (is (= [:notification :add] (c/topic (c/->Notification :add [1 2])))) ; c/topic
  (is (thrown? js/Error (c/msp-rpc-array->message [2 "add" nil]))) ; invalid args
  (is (thrown? js/Error (c/msp-rpc-array->message [2 "" 1 [1 2]]))) ; empty method name
  (is (thrown? js/Error (c/msp-rpc-array->message [2 nil 1 [1 2]]))) ; nil method name
  (is (thrown? js/Error (c/msp-rpc-array->message [2 "add" 1 [1 2]]))) ; looks like a request
  (is (thrown? js/Error (c/msp-rpc-array->message [2 1 "some error" nil]))) ; looks like a c/response with error
  (is (thrown? js/Error (c/msp-rpc-array->message [2 1 nil "some result"]))) ; looks like a c/response with result
  )

(deftest subscription-mgmt-test
  (let [[ch1 ch2] [(chan) (chan)]]
    ; request
    (is (thrown? js/Error (c/subscribe-to-request! 1 ch1)))
    (is (thrown? js/Error (c/subscribe-to-request! "" ch1)))
    (is (thrown? js/Error (c/subscribe-to-request! (keyword "") ch1)))
    (is (thrown? js/Error (c/subscribe-to-request! :add nil)))
    (is (thrown? js/Error (c/subscribe-to-request! :add "I am a channel, I swear!")))
    (is (= true (c/subscribe-to-request! :add ch1)))
    (is (= ch1 (-> (c/get-request-subscriptions) (get :add))))
    (is (= false (c/subscribe-to-request! :add ch2)))
    (is (= true (c/unsubscribe-from-request! :add ch1)))
    (is (= nil (-> (c/get-request-subscriptions) (get :add))))
    (is (= true (c/subscribe-to-request! :add ch2)))
    (is (= ch2 (-> (c/get-request-subscriptions) (get :add))))
    (is (= false (c/unsubscribe-from-request! :add ch1)))
    (is (= true (c/unsubscribe-from-request! :add ch2)))
    (is (= nil (-> (c/get-request-subscriptions) (get :add))))
    
    ; default
    (is (= true (c/subscribe-to-request! ch1)))
    (is (= ch1 (:cljs-msgpack-mini-rpc.core/default (c/get-request-subscriptions))))
    (is (= true (c/unsubscribe-from-request! ch1)))
    (is (= nil (:cljs-msgpack-mini-rpc.core/default (c/get-request-subscriptions))))

    ; c/response
    (is (thrown? js/Error (c/subscribe-to-notification! 1 ch1)))
    (is (thrown? js/Error (c/subscribe-to-notification! "" ch1)))
    (is (thrown? js/Error (c/subscribe-to-notification! (keyword "") ch1)))
    (is (thrown? js/Error (c/subscribe-to-notification! :add nil)))
    (is (thrown? js/Error (c/subscribe-to-notification! :add "I am a channel, I swear!")))
    (is (= true (c/subscribe-to-notification! :add ch1)))
    (is (= ch1 (-> (c/get-notification-subscriptions) (get :add))))
    (is (= false (c/subscribe-to-notification! :add ch2)))
    (is (= true (c/unsubscribe-from-notification! :add ch1)))
    (is (= nil (-> (c/get-notification-subscriptions) (get :add))))
    (is (= true (c/subscribe-to-notification! :add ch2)))
    (is (= ch2 (-> (c/get-notification-subscriptions) (get :add))))
    (is (= false (c/unsubscribe-from-notification! :add ch1)))
    (is (= true (c/unsubscribe-from-notification! :add ch2)))
    (is (= nil (-> (c/get-notification-subscriptions) (get :add))))

    ; default
    (is (= true (c/subscribe-to-notification! ch1)))
    (is (= ch1 (:cljs-msgpack-mini-rpc.core/default (c/get-notification-subscriptions))))
    (is (= true (c/unsubscribe-from-notification! ch1)))
    (is (= nil (:cljs-msgpack-mini-rpc.core/default (c/get-notification-subscriptions))))

    ; c/response
    (is (thrown? js/Error (c/subscribe-to-response! :add ch1)))
    (is (thrown? js/Error (c/subscribe-to-response! (c/->Request -1 :add [1 2]) ch1)))
    (is (thrown? js/Error (c/subscribe-to-response! -1 ch1)))
    (is (thrown? js/Error (c/subscribe-to-response! 1 nil)))
    (is (thrown? js/Error (c/subscribe-to-response! 2 "I am a channel, I swear!")))
    (doseq [r [1 (c/->Request 1 :add [1 2])]]
      (is (= true (c/subscribe-to-response! r ch1)))
      (is (= ch1 (-> (c/get-response-subscriptions) (get 1))))
      (is (= false (c/subscribe-to-response! r ch2)))
      (is (= true (c/unsubscribe-from-response! r ch1)))
      (is (= nil (-> (c/get-response-subscriptions) (get 1))))
      (is (= true (c/subscribe-to-response! r ch2)))
      (is (= ch2 (-> (c/get-response-subscriptions) (get 1))))
      (is (= false (c/unsubscribe-from-response! r ch1)))
      (is (= true (c/unsubscribe-from-response! r ch2)))
      (is (= nil (-> (c/get-response-subscriptions) (get 1)))))

    ; default
    (is (= true (c/subscribe-to-response! ch1)))
    (is (= ch1 (:cljs-msgpack-mini-rpc.core/default (c/get-response-subscriptions))))
    (is (= true (c/unsubscribe-from-response! ch1)))
    (is (= nil (:cljs-msgpack-mini-rpc.core/default (c/get-response-subscriptions))))

    (close! ch1)
    (close! ch2)))

(deftest attach-detach-routing-test
  (let [[raw-in raw-out] [(chan) (chan)]]
    (is (thrown? js/Error (c/attach nil nil)))
    (is (thrown? js/Error (c/attach "I am a channel, I swear!" raw-out)))
    (is (thrown? js/Error (c/attach raw-in raw-out :in-ex-handler nil)))
    (is (thrown? js/Error (c/attach raw-in raw-out :in-ex-handler identity :in-ex-handler nil)))
    (is (thrown? js/Error (c/attach raw-in raw-out :in-ex-handler identity :out-ex-handler nil)))
    (async done
           (go
             (let [{in :in out :out :as attachment} (c/attach raw-in raw-out)]

               (doseq [test-values [[[:request :add] (c/->Request 1 :add [1 2])]
                                    [[:response 1] (c/->Response 1 nil 3)]
                                    [[:notification :do-something] (c/->Notification :do-something ["something"])]
                                    [[:request] (c/->Request 1 :unknown-method ["some" "args"])]
                                    [[:response] (c/->Response 291 nil "unknown response")]
                                    [[:notification] (c/->Notification :unknown-method ["some" "args"])]]
                       :let [ch (chan)
                             subscribe-args (-> test-values first (conj ch))
                             msg-type (first subscribe-args)
                             message (second test-values)]]
                 (testing (str "routing of " msg-type)
                   (is (= true (apply c/subscribe! subscribe-args)))
                   (is (= true (>! out message)))
                   (let [arr (<! raw-out)]
                     (is (= (c/message->msp-rpc-array message) arr))
                     (>! raw-in arr))
                   (is (= message (second (<! ch))))
                   (apply c/unsubscribe! subscribe-args)
                   (close! ch)))

               ;; clean up
               (c/detach attachment)
               (is (nil? (<! in)))
               (is (false? (>! out "some value")))

               (close! raw-in)
               (close! raw-out)
               (done))))))

(deftest routing-exception-handling-test
  (async done
         (go
         (let [[raw-in raw-out] [(chan) (chan)]
               in-error-ch (chan)
               out-error-ch (chan)
               put-on-error-ch! (fn [ch attachment err] (do (go (>! ch err)) nil))
               {in :in out :out :as attachment} (c/attach raw-in raw-out 
                                                        :in-ex-handler (partial put-on-error-ch! in-error-ch) 
                                                        :out-ex-handler (partial put-on-error-ch! out-error-ch))]
           (doseq [in-array [[3] [0 1 "add" nil] [0 "" 1 [1 2]] [1 -1 nil nil] [1 1 "error" "result"] [2 "" []] [2 "add" nil]]]
             (>! raw-in in-array)
             (let [err-type (-> in-error-ch <! type)]
               (is (or (= js/Error err-type) (= ExceptionInfo err-type)))))
           (doseq [out-data [[0 1 "add" [1 2]] 
                             (assoc (c/->Request 1 :add [1 2]) :method (keyword "")) ; invalid request
                             (assoc (c/->Request 1 :add [1 2]) :msg-id -1) ; invalid request
                             (assoc (c/->Request 1 :add [1 2]) :args 1) ; invalid request
                             (assoc (c/->Response 1 nil nil) :msg-id -1) ; invalid c/response
                             (assoc (c/->Response 1 nil nil) :error "some error" :result "some result") ; invalid c/response
                             (assoc (c/->Notification :add [1 2]) :method (keyword "")) ; invalid notification
                             (assoc (c/->Notification :add [1 2]) :args 1) ; invalid notification
                             ]]
             (>! out out-data)
             (let [err-type (-> out-error-ch <! type)]
               (is (or (= js/Error err-type) (= ExceptionInfo err-type)))))
           (c/detach attachment)
           (close! in)
           (close! out)
           (done)))))

(deftest helpers-test
  ; request
  (is (= (c/->Request 1 :add [1 1]) (c/request :add 1 1)))
  (is (thrown? js/Error (c/request (keyword "") 1 1)))

  ; c/response
  (let [request (c/->Request 1 :add [1 1])]
    (is (= (c/response request :result 2) (c/->Response 1 nil 2)))
    (is (= (c/response request :error "some error") (c/->Response 1 "some error" nil)))
    (is (thrown? js/Error (c/response (c/->Response 1 nil nil) :result "some result")))
    (is (thrown? js/Error (c/response request :foo "some result"))))

  ; notification
  (is (= (c/->Notification :add [1 1]) (c/notification :add 1 1)))
  (is (thrown? js/Error (c/notification (keyword "") 1 1))))

(deftest response-ch-test
  (async done
         (let [[raw-in raw-out] [(chan) (chan)]
               add-ch (chan)
               {out :out :as attachment} (c/attach raw-in raw-out)]
           (pipe raw-out raw-in)
           (is (= true (c/subscribe-to-request! :add add-ch)))
           (go
             (let [[{out :out} {args :args :as request}] (<! add-ch)]
               (>! out (c/response request :result (apply + args)))))
           (go
             (let [request (c/request :add 1 2)
                   response-ch (c/response-ch request)]
               (>! out request)
               (is (= (<! response-ch) [nil 3])))
             (c/detach attachment)
             (doseq [ch [raw-in raw-out add-ch]]
               (close! ch))
             (is (= true (c/unsubscribe-from-request! :add add-ch)))
             (done)))))
