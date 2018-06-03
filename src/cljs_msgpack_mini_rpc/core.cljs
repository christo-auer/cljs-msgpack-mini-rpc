(ns cljs-msgpack-mini-rpc.core
  (:require [cljs.core.async :refer [chan pipe pub sub mix admix unmix unsub promise-chan close!]]
            [cljs.spec.alpha :as s]
            [cljs.spec.test.alpha :as st]
            [clojure.string :refer [blank?]]
            [cljs.core.async.impl.protocols :refer [Channel]]))

(def msg-type->type-id
  ^:private
  {:request 0
   :response 1
   :notification 2})

(def msg-type-id->msg-type
  ^:private
  {0 :request
   1 :response
   2 :notification})

; specs
(s/def ::msg-type-id #{0 1 2})
(s/def ::request-type-id (partial = (:request msg-type->type-id)))
(s/def ::response-type-id (partial = (:response msg-type->type-id)))
(s/def ::notification-type-id (partial = (:notification msg-type->type-id)))
(s/def ::msg-type #{:response :notification :request})
(s/def ::msg-type-request #(= % :request))
(s/def ::msg-type-notification #(= % :notification))
(s/def ::msg-type-response #(= % :response))
(s/def ::request-or-notification #{:notification :request})
(s/def ::method (s/and keyword? #(-> % name blank? not)))
(s/def ::method-string (s/and string? #(not (blank? %))))
(s/def ::msg-id (s/and integer? #(pos? %)))
(s/def ::arg any?)
(s/def ::args (s/coll-of ::arg :kind vector?))
(s/def ::Channel #(satisfies? Channel %))
(s/def ::error any?)
(s/def ::result any?)
(s/def ::msp-rpc-request (s/tuple ::request-type-id ::msg-id ::method-string ::args))
(s/def ::msp-rpc-response (s/and (s/tuple ::response-type-id ::msg-id ::error ::result) #(or (-> 2 % nil?) (-> 3 % nil?))))
(s/def ::msp-rpc-notification (s/tuple ::notification-type-id ::method-string ::args))
(s/def ::msp-rpc-array (s/or ::request? ::msp-rpc-request ::notification? ::msp-rpc-notification ::response? ::msp-rpc-response))
(s/def ::response-type #{:result :error})
(s/def ::ex-handler fn?)
(s/def ::in ::Channel)
(s/def ::out ::Channel)
(s/def ::raw-in ::Channel)
(s/def ::raw-out ::Channel)
(s/def ::in-ex-handler ::ex-handler)
(s/def ::out-ex-handler ::ex-handler)

(defprotocol Msp-RPC-Message
  ^:private
  (message->msp-rpc-array [this])
  (topic [this]))

; Request
(defn- request->msp-rpc-array [request]
  [0 (:msg-id request) (-> request :method name)  (:args request)])

(defrecord Request [msg-id method args]
  Msp-RPC-Message
  (message->msp-rpc-array [request]
    (request->msp-rpc-array request))
  (topic [request]
    [:request (:method request)]))

(s/def ::Request (s/and #(instance? Request %) (s/keys :req-un [::method ::msg-id ::args])))

(s/fdef
  ->Request
  :args (s/cat :msg-id ::msg-id :method ::method :args ::args)
  :ret ::Request)

(s/fdef
  request->msp-rpc-array
  :args (s/cat :request ::Request)
  :ret ::msp-rpc-request)

; Response
(defn- response->msp-rpc-array [response]
  [1 (:msg-id response) (:error response) (:result response)])

(defrecord Response [msg-id error result]
  Msp-RPC-Message
  (message->msp-rpc-array [response]
    (response->msp-rpc-array response))
 (topic [response]
    [:response (:msg-id response)]))

(s/def ::Response (s/and  #(instance? Response %)
                         (s/keys :req-un [::msg-id ::error ::result]) #(or (-> % :error nil?) (-> % :result nil?))))
(s/fdef
  response->msp-rpc-array
  :args (s/cat :response ::Response)
  :ret ::msp-rpc-response)

(s/fdef
  ->Response
  :args (s/cat :msg-id ::msg-id :error ::error :result ::result)
  :ret ::Response)

; Notification
(defn- notification->msp-rpc-array [notification]
  [2 (-> notification :method name) (:args notification)])

(defrecord Notification [method args]
  Msp-RPC-Message
  (message->msp-rpc-array [notification]
    (notification->msp-rpc-array notification))
  (topic [notification]
    [:notification (:method notification)]))

(s/def ::Notification (s/and #(instance? Notification %) (s/keys :req-un [::method ::args])))
(s/fdef
  ->Notification
  :args (s/cat :method ::method :args ::args)
  :ret ::Notification)

(s/fdef
  notification->msp-rpc-array
  :args (s/cat :notification ::Notification)
  :ret ::msp-rpc-notification)

; spec messages
(s/def ::message (s/or ::request? ::Request ::notification? ::Notification ::response? ::Response))

; for decoding MSP arrays, we use a multimethod
(defmulti msp-rpc-array->message
  (fn [[msg-type-id & _]]
    (get msg-type-id->msg-type msg-type-id)))

; since multi methods cannot be spec'ed (yet), we have to spec an
; ordinary function which the multi method calls (the same for responses
; and notifications)
(defn- msp-rpc-request->request [msp-rpc-array]
  (apply ->Request (update (vec (rest msp-rpc-array)) 1 keyword)))

(s/fdef msp-rpc-request->request
        :args (s/cat :msp-rpc-request ::msp-rpc-request)
        :ret ::Request)

(defmethod msp-rpc-array->message :request [msp-rpc-array]
  (msp-rpc-request->request msp-rpc-array))

(defn- msp-rpc-response->response [msp-rpc-array]
  (apply ->Response (rest msp-rpc-array)))

(s/fdef msp-rpc-response->response
        :args (s/cat :msp-rpc-response ::msp-rpc-response)
        :ret ::Response)

(defmethod msp-rpc-array->message :response [msp-rpc-array]
  (msp-rpc-response->response msp-rpc-array))

(defn- msp-rpc-notification->notification [msp-rpc-array]
  (apply ->Notification (update (vec (rest msp-rpc-array)) 0 keyword)))

(s/fdef msp-rpc-notification->notification
        :args (s/cat :msp-rpc-notification ::msp-rpc-notification)
        :ret ::Notification)

(defmethod msp-rpc-array->message :notification [msp-rpc-array]
  (msp-rpc-notification->notification msp-rpc-array))

; global input channel/mix
(def ^:private in-channels (chan))

(def ^:private in-mix (mix in-channels))


; subscriptions
(defonce ^:private subscriptions
  (atom
    {:request {}
     :notification {}
     :response {}}))

; publications
(defn- topic-fn [message]
  (let [[msg-type _ :as msg-topic] (topic message)]
    (if (get-in @subscriptions msg-topic)
      msg-topic
      [msg-type ::default])))

(defonce  ^:private publications
 (pub in-channels (comp topic-fn second)))

; message id counter
(defonce ^:private msg-id (atom 0))

(defn- next-msg-id! []
  (swap! msg-id inc))

; helpers for subscriptions
(defn- subscribe!
  ([msg-type id ch]
   (if-not (get-in @subscriptions [msg-type id])
     (do
       (sub publications [msg-type id] ch)
       (swap! subscriptions assoc-in [msg-type id] ch)
       true)
     false))
  ([msg-type ch]
   (subscribe! msg-type ::default ch)))

(defn- unsubscribe!
  ([msg-type id ch]
   (if (= (get-in @subscriptions [msg-type id]) ch)
     (do
       (unsub publications [msg-type id] ch)
       (swap! subscriptions update msg-type dissoc id)
       true)
     false))
  ([msg-type ch]
   (unsubscribe! msg-type ::default ch)))

(defn get-request-subscriptions []
  "Returns all request subscriptions as a map from the method
  to the subscribing channel."
  (:request @subscriptions))

(defn subscribe-to-request!
  "`[method ch]` subscribes channel `ch` to method `method`
  (`keyword`), returns `true` if successful, `false` if
  there is already a channel subscribed to `method`

  `[ch]` subscribes channel `ch` to the default request
  (`::default`), i.e., every received request to which no
  channel has subscribed is passed to `ch`. Returns `true`
  if successful, `false` if another channel has already
  subscribed."
  ([method ch]
   (subscribe! :request method ch))
  ([ch]
   (subscribe! :request ch)))

(s/fdef
  subscribe-to-request!
  :args (s/cat :method (s/? ::method) :Channel ::Channel)
  :ret #{true false})

(defn unsubscribe-from-request!
  "`[method ch]` unsubscribes channel `ch` from method `method`,
  returns `true` if successful, `false` if `ch` is not subscribed
  to `method`.

  `[ch]` unsubscribes channel `ch` from the default request
  (`::default`). Returns `true` if successful, `false` if another
  channel has subscribed."
  ([method ch]
   (unsubscribe! :request method ch))
  ([ch]
   (unsubscribe! :request ch)))

(s/fdef
  unsubscribe-from-request!
  :args (s/cat :method (s/? ::method) :channel ::Channel)
  :ret #{true false})

(defn get-response-subscriptions []
  "Returns all response subscriptions as a map from the msg-id
  to subscribing channel."
  (:response @subscriptions))

(defn subscribe-to-response!
  "`[msg-id-or-request ch]`: `msg-id-or-request` is an `int`
  (msg-id) or a `Request`.  Subscribes channel `ch` to the
  corresponding msg-id.  Returns `true` if successful, `false`
  if there is already a channel subscribed to `msg-id`

  `[ch]` subscribes channel `ch` to the default msg-id
  (`::default`), i.e., every received resonse to which no
  channel has subscribed is passed to `ch`. Returns `true`
  if successful, `false` if another channel has already
  subscribed."
  ([msg-id-or-request ch]
   (let [msg-id (if (instance? Request msg-id-or-request)
                  (:msg-id msg-id-or-request)
                  msg-id-or-request)]
     (subscribe! :response msg-id ch)))
  ([ch]
   (subscribe! :response ch)))

(s/fdef
  subscribe-to-response!
  :args (s/cat :msg-id-or-request (s/? (s/or :msg-id ::msg-id :request ::Request)) :ch ::Channel)
  :ret #{true false})

(defn unsubscribe-from-response!
  "`[msg-id-or-request ch]` unsubscribes channel `ch`
  from the corresponding msg-id, returns `true` if
  successful, `false` if `ch` is not subscribed to
  `msg-id`.

  `[ch]` unsubscribes channel `ch` from the default msg-id
  (`::default`). Returns `true` if successful, `false` if another
  channel has subscribed."
  ([msg-id-or-request ch]
   (let [msg-id (if (instance? Request msg-id-or-request)
                  (:msg-id msg-id-or-request)
                  msg-id-or-request)]
     (unsubscribe! :response msg-id ch)))
  ([ch]
   (unsubscribe! :response ch)))

(s/fdef
  unsubscribe-from-response!
  :args (s/cat :msg-id-or-request (s/? (s/or :msg-id ::msg-id :request ::Request)) :Channel ::Channel)
  :ret #{true false})

(defn get-notification-subscriptions []
  "Returns all notification subscriptions as a map from the
  method to subscribing channel."
  (:notification @subscriptions))

(defn subscribe-to-notification!
  "`[method ch]` subscribes channel `ch` to method `method`
  (`keyword`), returns `true` if successful, `false` if
  there is already a channel subscribed to `method`

  `[ch]` subscribes channel `ch` to the default notification
  (`::default`), i.e., every received notification to which
  no channel has subscribed is passed to `ch`. Returns `true`
  if successful, `false` if another channel has already
  subscribed."
  ([method ch]
   (subscribe! :notification method ch))
  ([ch]
   (subscribe! :notification ch)))

(s/fdef
  subscribe-to-notification!
  :args (s/cat :method (s/? ::method) :ch ::Channel)
  :ret #{true false})

(defn unsubscribe-from-notification!
  "`[method ch]` unsubscribes channel `ch` from method `method`,
  returns `true` if successful, `false` if `ch` is not subscribed
  to `method`.

  `[ch]` unsubscribes channel `ch` from the default notification
  (`::default`). Returns `true` if successful, `false` if another
  channel has subscribed."
  ([method ch]
   (unsubscribe! :notification method ch))
  ([ch]
   (unsubscribe! :notification ch)))

(s/fdef
  unsubscribe-from-notification!
  :args (s/cat :method (s/? ::method) :ch ::Channel)
  :ret #{true false})

; attachment
(defrecord Attachment [in out raw-in raw-out])
(s/fdef
  ->Attachment
  :args (s/cat :in ::in :out ::out :raw-in ::raw-in :raw-out ::raw-out))
(s/def ::Attachment (s/and #(instance? Attachment %) (s/keys :req-un [::in ::out ::raw-in ::raw-out])))

(defn attach
  "Attaches `raw-in` (`Channel`) and `raw-out` (`Channel`) to the
  global RPC routing pipeline.

  `raw-in` must deliver msgpack RPC arrays according to the format
  defined at
  https://github.com/msgpack-rpc/msgpack-rpc/blob/master/spec.md
  Accordingly, msgpack RPC arrays are put on `raw-out`.

  `attach` returns an `Attachment`, a record, with the fields
  - `raw-in`/`raw-out` the input parameters of attach
  - `in`, the input channel that receives the msgpack RPC messages in a
  higher level format (records `Request`, `Response`, and `Notification`)
  NOTE: do not directly take (or put) values from `in` as `in` is in a
  channel `mix` for global routing!
  - `out`, the output channel to which RPC messages can be put for
  conversion to RPC msgpack arrays and are delivered to `raw-out`

  `attach` accepts optional parameters `:in-ex-handler ex-handler`
  and `:out-ex-handler ex-handler`, where `ex-handler` is a function
  that takes parameters `[attachment exception]`. If an exception
  occurs on the way from `raw-in` to `in` or `out` to `raw-out`,
  `ex-handler` is called. The return value of `ex-handler` is put
  on `in` or `raw-out`, respectively. If `ex-handler` returns `nil`
  no value is put."
  [raw-in raw-out &
   {in-ex-handler :in-ex-handler
    out-ex-handler :out-ex-handler
    :or {:in-ex-handler (constantly nil) :out-ex-handler (constantly nil)}}]
  (let [attachment-atom (atom nil)
        out (chan 1
                  (map message->msp-rpc-array)
                  #(out-ex-handler @attachment-atom %))
        in (chan 1
                 (map (fn [msp-rpc-array] [@attachment-atom (msp-rpc-array->message msp-rpc-array)]))
                 #(in-ex-handler @attachment-atom %))
        attachment (->Attachment in out raw-in raw-out)]
    (pipe raw-in in)
    (pipe out raw-out false)
    (admix in-mix in)
    (reset! attachment-atom attachment)))

(s/fdef
  attach
  :args (s/cat :raw-in ::Channel :raw-out ::Channel :ex-handlers (s/keys* :opt-un [::in-ex-handler ::out-ex-handler]))
  :ret ::Attachment)

(defn detach
  "Removes `attachment` from the global RPC routing and closes
  `in` and `out` of `attachment`. Note that `raw-in` and `raw-out`
  are not closed by `detach`."
  [attachment]
  (unmix in-mix (:in attachment))
  (close! (:in attachment))
  (close! (:out attachment)))

(s/fdef
  detach
  :args (s/cat :attachment ::Attachment))

; request
(defn request
  "Takes the name of a method (`keyword`) and arguments and
  constructs a `Request` that can be put to `out` of an
  attachment."
  [method & args]
  (->Request (next-msg-id!) method (vec args)))

(s/fdef
  request
  :args (s/cat :method ::method :args (s/* ::arg))
  :ret ::Request)

; notification
(defn notification
  "Takes the name of a method (`keyword`) and arguments and
  constructs a `Notification` that can be put to `out` of an
  attachment."
  [method & args]
  (->Notification method (vec args)))

(s/fdef
  notification
  :args (s/cat :method ::method :args (s/* ::arg))
  :ret ::Notification)

; response
(defn response [{:keys [msg-id]} response-type value]
  "Takes a `Request` and the type of the response (`:result` or `:error`)
  along with a value and constructs a response."
  (let [[error result] (case response-type
                         :result [nil value]
                         :error  [value nil])]
    (->Response msg-id error result)))

(s/fdef
  response
  :args (s/cat :request ::Request :response-type ::response-type :value ::arg)
  :ret ::Response)

; response channel
(defn response-ch
  "Takes a request and creates a `promise-chan` that receives the
  response when it is answered. The value taken from the channel
  has the format `[error result]`. If  `error` is `nil` then `result`
  contains the result, otherwise `error` contains the error and
  `result` is `nil`.  Optionally, a function `ex-handler` can be
  provided that is passed to `promise-chan`.
  Note: You have to take care that the returned channel is
  unsubscribed as response handler after receiving the result."
  ([request ex-handler]
   (let [{:keys [msg-id]} request
         response-ch (promise-chan (map (comp (juxt :error :result) second)) ex-handler)]
     (subscribe-to-response! msg-id response-ch)
     response-ch))

  ([request]
   (response-ch request identity)))

(s/fdef
  response-ch
  :args (s/cat :request ::Request :ex-handler (s/? ::ex-handler))
  :ret ::Channel)

(st/instrument `cljs-msgpack-mini-rpc.core)
