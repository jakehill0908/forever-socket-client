(ns forever-socket-client.core
  (:import [java.net Socket InetAddress InetSocketAddress SocketException])
  (:require [clojure.core.async :refer [put! <! chan go-loop timeout]]))

(declare start-socket-read!)
(declare socket-write)
(declare socket)

(defn db-factory
  "Build map containing socket peripherals"
  [^Socket sock ^Integer read-buffer-size]
  {:read-channel (start-socket-read! (.getInputStream sock) read-buffer-size)
   :write (socket-write (.getOutputStream sock))
   :socket sock
   :buffer-size read-buffer-size})

(defn socket-factory
  "Instantiate a java socket with SO_KEEPALIVE"
  ([^String host ^Integer port]
   (let [sock (Socket. (InetAddress/getByName host) port)]
     (.setKeepAlive sock true)
     sock))
  ([^InetSocketAddress host]
   (let [sock (Socket. (.getAddress host) (.getPort host))]
     (.setKeepAlive sock true)
     sock)))

(defn socket-watcher
  "Watch socket to make sure it is not closed, if it is attempt to reconnect"
  [interval socket-db]
  (let [poisoned (atom false)
        stop-watcher (fn [] (reset! poisoned true))
        socket-atom (atom (assoc socket-db :disconnect stop-watcher))
        reconnect (fn []
                    (let [sock (:socket @socket-atom)
                          buffer-size (:buffer-size @socket-atom)
                          address (.getRemoteSocketAddress sock)]
                      (try
                        (reset! socket-atom
                                (db-factory
                                  (socket-factory address)
                                  buffer-size))
                        (catch SocketException e))))]
    (go-loop []
      (println "Checking...")
      (<! (timeout interval))
      (if (.isClosed (:socket @socket-atom)) ; TODO: this is actually worthless
        (do
          (println "Attempting Reconnect...")
          (.close (:socket @socket-atom))
          (reconnect)))
      (if @poisoned
        (.close (:socket @socket-atom))
        (recur)))
    socket-atom))

(defn socket
  "Instantiate a java socket with SO_KEEPALIVE"
  ([^String host ^Integer port ^Integer read-buffer-size ^Integer watcher-interval]
   (socket-watcher watcher-interval
     (db-factory (socket-factory host port) read-buffer-size)))
  ([^String host ^Integer port ^Integer read-buffer-size]
   (socket-watcher 1000
     (db-factory (socket-factory host port) read-buffer-size)))
  ([^String host ^Integer port]
   (socket-watcher 1000
     (db-factory (socket-factory host port) 2048))))

(defn socket-write
  "Closure for simpler write to socket"
  [output-stream]
  (let [send-to-socket (fn [data]
                         (.write output-stream data 0 (count data))
                         (.flush output-stream))]
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
                         (println "Socket Closed")
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
