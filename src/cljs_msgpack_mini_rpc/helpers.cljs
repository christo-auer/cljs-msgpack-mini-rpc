(ns cljs-msgpack-mini-rpc.helpers
  (:require [cljs.core.async :refer-macros [go go-loop] :refer [>! <! chan]]
            [cljs.spec.alpha :as s]
            [cljs.spec.test.alpha :as st]
            [cljs-msgpack-mini-rpc.core :as core]))

(defn request! [{out :out} method & args]
  "Takes an `core/Attachment`, a method (`keyword`) and arguments and
  puts a request onto the attachment. Returns a channel onto which
  `[error result]` is put as with `core/response-ch`.
  If the request cannot be put onto the attachment, an `ex-info` is
  returns for `error`."
  (go
    (let [request (apply core/request method args)
          response-ch (core/response-ch request)]
      (if (>! out request)
        (let [result (<! response-ch)]
          (core/unsubscribe-from-response! (:msg-id request) response-ch)
          result)
        [(ex-info "Unable to put request on out channel." {}) nil]))))

(s/fdef
  request!
  :args (s/cat :attachment ::core/Attachment :method ::core/method :args (s/* ::core/arg)))

(defn notify! [{out :out} method & args]
  "Takes an `core/Attachment`, a method (`keyword`) and arguments and
  puts a notification onto the attachment. Returns a channel onto which
  `true` is put if the notification was put successfully, otherwise
  an `ex-info`."
  (go
    (let [notification (apply core/notification method args)]
      (if-not (>! out notification)
        (ex-info "Unable to put notification on out channel." {})
        true))))

(s/fdef
  notify!
  :args (s/cat :request ::core/Attachment :method ::core/method :args (s/* ::core/arg)))

(defn register-request-handler!
  "Registers a handler function for the given method (`keyword`) to which
  `channel` has subscribed. `handler-fn` is called
  with the args passed to the request and  must return
  a vector `[response-type value]`, where either `response-type` is
  `:result` and `value` contains the result upon success or `response-type`
  is `:error` and `value` is an error.
  To stop `handler-fn `from handling requests `channel ` must be
  `cljs.core.asyn/close!`ed.

  Note that each call of this function 'spawns' a `go-loop`."
  [method channel handler-fn]
  (if (core/subscribe-to-request! method channel)
    (go-loop []
             (when-let [[{out :out} request] (<! channel)]
               (let [result (apply handler-fn (:args request))
                     response (apply core/response request result)]
                 (>! out response)
                 (recur)))
             (core/unsubscribe-from-request! method channel))
    nil))

(s/fdef
  register-request-handler!
  :args (s/cat :method ::core/method :channel ::core/Channel  :handler-fn fn?))

(defn register-notification-handler!
  "Registers a handler function for the given method (`keyword`) to which
  `channel` has subscribed. `handler-fn` is called
  with the args passed to the notification.
  To stop `handler-fn `from handling notifications, `channel ` must be
  `cljs.core.asyn/close!`ed.

  Note that each call of this function 'spawns' a `go-loop`."
  ([method channel handler-fn]
   (if (core/subscribe-to-notification! method channel)
     (go-loop []
              (when-let [[_ notification] (<! channel)]
                (apply handler-fn (:args notification))
                (recur))
              (core/unsubscribe-from-notification! method channel))
     nil)))

(s/fdef
  register-notification-handler!
  :args (s/cat :method ::core/method :channel ::core/Channel  :handler-fn fn?))

(st/instrument `cljs-msgpack-mini-rpc.helpers)
