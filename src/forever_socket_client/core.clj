(ns forever-socket-client.core
  (:import [java.net Socket InetAddress InetSocketAddress SocketException ConnectException])
  (:require [clojure.core.async :refer [put! <! chan go-loop timeout]]))

(declare start-socket-read!)
(declare socket-write)
(declare socket)

(defn setup-read-callback
  "Run a supplied callback with received data"
  [sock data-recv-cb]
  (go-loop []
    (let [data (<! (:read @sock))] ; Read data from socket stream
      (if (= (type (chan)) (type data)) ; If it is a channel the socket has disconnected
        (<! data) ; Wait for reconnect
        (data-recv-cb data)) ; Send received data to callback
      (recur))))

(defn write-to-socket
  [sock data]
  ((:write @sock) data))

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
  "Watch for socket close event and attempt to reconnect"
  [retry-interval buffer-size sock]
  (let [close-notify-chan (chan)
        reconnected-notify-chan (atom nil)
        close-notify (fn [reconnected-chan] ; Start retry loop, takes a new channel that can be used to unblock reader
                       (do
                         (reset! reconnected-notify-chan reconnected-chan)
                         (put! close-notify-chan :closed))) ; Allow read to signal socket closed
        socket-atom (atom {:socket sock
                           :read (start-socket-read! (.getInputStream sock) buffer-size close-notify)
                           :write (socket-write (.getOutputStream sock))})]
    (go-loop []
      (<! close-notify-chan) ; Wait for socket to close
      (<! (timeout retry-interval)) ; Wait for retry interval
      (try ; Attempt to reconnect
        (let [new-socket (socket-factory (.getRemoteSocketAddress (:socket @socket-atom)))]
          (reset! socket-atom {:socket new-socket ; Update socket atom with new connection
                               :read (start-socket-read! (.getInputStream new-socket) buffer-size close-notify)
                               :write (socket-write (.getOutputStream new-socket))})
          (put! @reconnected-notify-chan :reconnected)) ; Notify read-hook of reconnection
        (catch ConnectException _
          (put! close-notify-chan :closed))) ; close-notify-chan should not block since connection is still closed
      (recur))
    socket-atom))

(defn socket
  "Instantiate a socket with watcher which will attempt to reconnect"
  ([^String host ^Integer port ^Integer read-buffer-size ^Integer retry-interval]
   (socket-watcher retry-interval read-buffer-size
                   (socket-factory host port)))
  ([^String host ^Integer port]
   (socket-watcher 5000 2048
                   (socket-factory host port))))

(defn socket-write
  "Closure for simpler write to socket"
  [output-stream]
  (fn [data]
    (.write output-stream data 0 (count data))
    (.flush output-stream)))

(defn start-socket-read!
  "Setup a receive channel and shuffle data to it on receive"
  [input-stream buffer-size close-notify]
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
          (let [reconnected-channel (chan)]
            (put! read-channel reconnected-channel)
            (close-notify reconnected-channel)
            nil)
          (do
            (put! read-channel data)
            (recur)))))
    read-channel))

(defn str-to-bytes
  "Convert str to bytes"
  [^String input]
  (byte-array (map (comp byte char) input)))

(defn bytes-to-str
  "Convert bytes to str"
  [data]
  (apply str (map char data)))
