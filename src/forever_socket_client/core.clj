(ns forever-socket-client.core
  (:import [java.net Socket InetAddress SocketException])
  (:require [clojure.core.async :refer [put! chan <! >! <!! go-loop]]))

(declare start-socket-read!)
(declare socket-write)

(defn socket
  "Instantiate a java socket with SO_KEEPALIVE"
  [^String host ^Integer port ^Integer read-buffer-size]
  (let [sock (Socket. (InetAddress/getByName host) port)]
    (.setKeepAlive sock true)
    {:read-channel (start-socket-read! (.getInputStream sock) read-buffer-size)
     :write (socket-write (.getOutputStream sock))
     :socket sock}))

(defn socket-write
  "Closure for simpler write to socket"
  [output-stream]
  (let [send-to-socket (fn [data]
                         (.write output-stream data 0 (count data)))]
    send-to-socket))

(defn start-socket-read!
  "Setup a receive channel and shuffle data to it on receive"
  [input-stream buffer-size]
  (let [read-channel (chan)
        start-read (fn [raw-buffer]
                     (try
                       (let [count (.read input-stream raw-buffer)]
                         (byte-array (take count raw-buffer)))
                       (catch SocketException e
                           :closed)))]
    (go-loop []
      (let [data (start-read (byte-array buffer-size))]
        (if (= data :closed)
          nil
          (do
            (put! read-channel data)
            (recur)))))
    read-channel))

(defn str-to-bytes
  "Convert things to bytes"
  [^String input]
  (byte-array (map (comp byte char) input)))
