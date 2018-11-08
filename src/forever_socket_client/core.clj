(ns forever-socket-client.core
  (:import [java.net Socket InetAddress SocketException]
           [java.io ByteArrayInputStream ByteArrayOutputStream])
  (:require [clojure.core.async :refer [put! chan <! >! <!! go-loop]]))

(defn socket
  "Instantiate a java socket with SO_KEEPALIVE"
  [^String host ^Integer port]
  (let [sock (Socket. (InetAddress/getByName host) port)]
    (.setKeepAlive sock true)
    sock))

(defn start-socket-read!
  [input-stream buffer-size stream-closed-cb]
  (let [read-channel (chan)
        start-read (fn [raw-buffer]
                     (try
                       (let [count (.read input-stream raw-buffer)]
                         (take count raw-buffer))
                       (catch SocketException e
                         (do
                           (stream-closed-cb)
                           :closed))))]
    (go-loop []
      (let [data (start-read (byte-array buffer-size))]
        (if (= data :closed)
          data
          (do
            (put! read-channel data)
            (recur)))))
    read-channel))

(defn structure
  "Prototyping"
  [^Socket socket]
  (let [ostream (.getOutputStream socket)
        istream (.getInputStream socket)]
    {:ostream ostream
     :istream istream
     :socket socket}))

(defn str-to-bytes
  "Convert things to bytes"
  [^String input]
  (byte-array (map (comp byte char) input)))
