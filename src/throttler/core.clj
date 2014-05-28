(ns throttler.core
  (:require [clojure.core.async :as async :refer [chan <!! >!! >! <! timeout go close! dropping-buffer]]
            [clojure.pprint :refer [pprint]]))

;; To keep the throttler precise even for high frequencies, we set up a
;; minimum sleep time. In my tests I found that below 10 ms the actual
;; sleep time has an error of more than 10%, so we stay above that.
(def ^{:no-doc true} min-sleep-time 10)

(defn- round [n] (Math/round (double n)))

(def ^{:no-doc true} unit->ms
  {:microsecond 0.001 :millisecond 1
   :second 1000 :minute 60000
   :hour 3600000 :day 86400000})

(defn mapply
  "Like apply, but applies a map to a function with positional map
  arguments. Can take optional initial args just like apply."
  ([f m] (apply f (apply concat m)))
  ([f arg & args] (apply f arg (concat (butlast args) (apply concat (last args))))))

(defmacro pipe [from to]
  "Pipes an element from the from channel and supplies it to the to
   channel. The to channel will be closed when the from channel closes.
   Must be called within a go block."
  `(let [v# (<! ~from)]
    (if (nil? v#)
      (close! ~to)
      (>! ~to v#))))

(defn chan-throttler* [rate-ms bucket-size token-value]
  ;(pprint ['chan-throttler* rate-ms bucket-size token-value])
  (let [sleep-time (max (/ token-value rate-ms) min-sleep-time)
        token-value (max (round (* sleep-time rate-ms)) token-value)
        bucket-size (max bucket-size token-value)   ; We have to make sure that at least token-value messages can fit
                                                    ; in the bucket. Otherwise, a large number of tokens can be lost
                                                    ; just because there was no reader - this can be particularly
                                                    ; important for large token-values.
        bucket (chan (dropping-buffer bucket-size)) ; Model the bucket with a buffered channel.
        sleep-time (int (round sleep-time))]        ; timeout expects an int, and will fail silently otherwise

    ;(pprint {:sleep-time sleep-time :token-value token-value :bucket-size bucket-size})

    ;; The bucket filler thread. Puts token-value tokens in the bucket every
    ;; sleep-time seconds. If the bucket is full the token is dropped
    ;; since the bucket channel uses a dropping buffer.

    ;; For high frequencies, we leave sleep-time fixed to
    ;; min-sleep-time, and we increase token-value, the number of
    ;; messages to pipe per token. For low frequencies, the token-value
    ;; is 1 and we adjust sleep-time to obtain the desired rate.

    (go
     (while true
       (dotimes [_ token-value] (>! bucket :token))
       (<! (timeout sleep-time))))

    ;; The piping thread. Takes a token from the bucket (blocking until
    ;; one is ready if the bucket is empty), and forwards one message
    ;; from the source channel to the output channel.
    (fn [c]
      (let [c' (chan)] ; the throttled chan
        (go
          (while true
            (<! bucket)   ; block for a token
            (pipe c c'))) ; pipe a single message
        c'))))

(defn- granularity->token-value [rate-ms g]
  (if (keyword? g)
    (do
      (assert (contains? unit->ms g)
              (str "Granularity " g " does not correspond to a known unit. Available units are: " (keys unit->ms)))
      (max (int (* (unit->ms g) rate-ms)) 1))
    (do
      (assert (integer? g) (str "Granularity " g " is neither a unit nor an integer"))
      (assert (pos? g) (str "Granularity value " g " should be positive"))
      g)))

(defn chan-throttler
  "Returns a function that will take an input channel and return an
   output channel with the desired rate. Optionally acceps a bucket size
   for bursty channels.

   If the throttling function returned here is used on more than one
   channel, they will all share the same token-bucket. This means their
   overall output rate combined will be equal to the provided rate. In
   other words, they will all share the alloted bandwith using
   statistical multiplexing.

   See fn-throttler for an example that can trivially be extrapolated to
   chan-throttler."

  ([rate unit]
     (chan-throttler rate unit 0))
  ([rate unit bucket-size & {granularity :granularity :or {granularity 1}}]
   ;(pprint [chan-throttler rate unit bucket-size granularity opts])
     (when (nil? (unit->ms unit))
       (throw (IllegalArgumentException.
               (str "Invalid unit. Available units are: " (keys unit->ms)))))

     (when-not (and (number? rate) (pos? rate))
       (throw (IllegalArgumentException. "rate should be a positive number")))

     (when (or (not (integer? bucket-size)) (neg? bucket-size))
       (throw (IllegalArgumentException. "bucket-size should be a non-negative integer")))

     (let [rate-ms (/ rate (unit->ms unit))]
       (chan-throttler* rate-ms bucket-size (granularity->token-value rate-ms granularity)))))

(defn throttle-chan
     "Takes a write channel, a goal rate and a unit and returns a read
      channel. Messages written to the input channel can be read from
      the throttled output channel at a rate that will be at most the
      provided goal rate.

      Optionally takes a bucket size, which will correspond to the
      maximum number of burst messages.

      As an example, the channel produced by calling:

      (throttle-chan (chan) 1 :second 9)

      Will transmit 1 message/second on average but can transmit up to
      10 messages on a single second (9 burst messages + 1
      message/second).

      Note that after the burst messages have been consumed they have to
      be refilled in a quiescent period at the provided rate, so the
      overall goal rate is not affected in the long term.

      The throttled channel will be closed when the input channel
      closes."

  ([c rate unit]
     (throttle-chan c rate unit 0))

  ([c rate unit bucket-size & {:as opts}]
   ;(pprint ['throttle-chan c rate unit bucket-size opts])
     ((mapply chan-throttler rate unit bucket-size opts) c)))

(defn fn-throttler

  "Creates a function that will globally throttle multiple functions at
   the provided rate.  The returned function accepts a function and
   produces an equivalent one that complies with the desired rate. If
   applied to many functions, the sum af all their invocations in a time
   interval will sum up to the goal average rate.

   Example:
       ; create the function throttler
       (def slow-to-1-per-minute (fn-throttler 1 :minute)

       ; create slow versions of f1, f2 and f3
       (def f1-slow (slow-to-1-per-minute f1)
       (def f2-slow (slow-to-1-per-minute f2)
       (def f3-slow (slow-to-1-per-minute f3)

       ; use them to do work. Their aggregate rate will be equal to 1
       ; call/minute
       (f1-slow arg1 arg2) ; => result, t = 0
       (f2-slow)           ; => result, t = 1 minute
       (f3-slow arg)       ; => result, t = 2 minutes

   The combined rate of f1-slow, f2-slow and f3-slow will be equal to
   'rate'. This does not mean that the rate of each is 1/3rd of
   'rate'; if only f1-slow is being called then its throughput will be
   close to rate. Or, if one of the functions is being called from
   multiple threads then it'll get a greater share of the total
   bandwith.

   In other words, the functions will use statistical multiplexing to
   cap the allotted bandwidth."

  ([rate unit]
     (fn-throttler rate unit 0))

  ([rate unit bucket-size & {:as opts}]
     ;(pprint ['fn-throttler rate unit bucket-size opts])
     (let [in (chan 1)
           out (mapply throttle-chan in rate unit bucket-size opts)]

       ;; This function takes a function and produces a throttled
       ;; function. When called multiple times, all the resulting
       ;; throttled functions will share the same throttled channel,
       ;; resulting in a globally shared rate. I.e., the sum af the
       ;; rates of all functions will be at most the argument rate).

       (fn [f]
         (fn [& args]
            ;; The approach is simple: pipe a bogus message through a
            ;; throttled channel before evaluating the original function.

           (>!! in :eval-request)
           (<!! out)
           (apply f args))))))

(defn throttle-fn

  "Takes a function, a goal rate and a time unit and returns a
  function that is equivalent to the original but that will have a maximum
  throughput of 'rate'.

  Optionally accepts a burst rate, in which case the resulting function
  will behave like a bursty channel. See throttle-chan for details."

  ([f rate unit]
     (throttle-fn f rate unit 0))

  ([f rate unit bucket-size & {:as opts}]
     ((mapply fn-throttler rate unit bucket-size opts) f)))