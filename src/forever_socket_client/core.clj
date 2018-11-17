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
      (if (= data :stopped)
        nil
        (do
          (cond
            (= (type (chan)) (type data)) (<! data) ; Wait for reconnect
            :else (data-recv-cb data))
          (recur)))))) ; Send received data to callback

(defn write-to-socket
  "Write bytes to a socket"
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

(defn socket-db-factory
  [sock buffer-size close-notify-fn stop-notify-fn]
  {:socket sock
   :read (start-socket-read! (.getInputStream sock) buffer-size close-notify-fn)
   :write (socket-write (.getOutputStream sock))
   :stop stop-notify-fn})

(defn socket-watcher
  "Watch for socket close event and attempt to reconnect"
  [retry-interval buffer-size sock]
  (let [close-notify-chan (chan)
        reconnected-notify-chan (atom nil)
        close-notify (fn [reconnected-chan] ; Start retry loop, takes a new channel that can be used to unblock reader
                       (do
                         (reset! reconnected-notify-chan reconnected-chan)
                         (put! close-notify-chan :closed))) ; Allow read to signal socket closed
        stop-notify (fn []
                      (put! close-notify-chan :stopped))
        socket-atom (atom (socket-db-factory sock buffer-size close-notify stop-notify))]
    (go-loop []
      (when-let [signal (<! close-notify-chan)]
        (println "Attempting to reconnect: " (.toString (.getRemoteSocketAddress (:socket @socket-atom))))
        (if (= signal :stopped)
          (let [{:keys [read socket]} @socket-atom]
            (put! read :stopped)
            (.close socket)
            nil)
          (do
            (try ; Attempt to reconnect
              (let [new-socket (socket-factory (.getRemoteSocketAddress (:socket @socket-atom)))]
                (reset! socket-atom (socket-db-factory new-socket buffer-size close-notify stop-notify))
                (put! @reconnected-notify-chan :reconnected)
                (println "Reconnected to: " (.toString (.getRemoteSocketAddress (:socket @socket-atom))))) ; Notify read-hook of reconnection
              (catch ConnectException _
                (<! (timeout retry-interval)) ; Wait for retry interval
                (put! close-notify-chan :closed)))
            (<! (timeout retry-interval))
            (recur)))))
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
                         (if (not= -1 count)
                           (byte-array (take count raw-buffer))
                           :closed))
                       (catch SocketException e
                         :closed)))]
    (go-loop []
      (let [data (start-read (byte-array buffer-size))]
        (if (= data :closed)
          (let [reconnected-channel (chan)]
            (put! read-channel reconnected-channel)
            (close-notify reconnected-channel)
            nil) ; Stop running read after signaling watcher
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
