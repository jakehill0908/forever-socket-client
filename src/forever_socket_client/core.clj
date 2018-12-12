(ns forever-socket-client.core
  (:import [java.net Socket InetAddress InetSocketAddress ConnectException])
  (:require [clojure.core.async :refer [put! <! chan go-loop timeout]]))

(defprotocol SocketIO
  "IO protocols for ForeverSocket record"
  (append-callback [this cb]) ; Add callback function with one arg for incoming data
  (start-reader! [this])      ; Start loop on thread pool to handle incoming data
  (read-stream [this])        ; Read stream and return either data or status
  (write [this data]))        ; Write bytes to socket

(defprotocol Stoppable
  "Implements stop function"
  (stop [this]))              ; Stop all threads and gracefully close socket

(defrecord ForeverSocket
  [socket buffer-size read-callbacks-atom event-channel]
  SocketIO
  (append-callback [_ cb]
    (reset! read-callbacks-atom
            (conj @read-callbacks-atom cb)))
  (write [_ data]
    (let [ostream (.getOutputStream socket)]
      (.write ostream data 0 (count data))
      (.flush ostream)))
  (start-reader! [this]
    (go-loop []
      (let [read-result (read-stream this)]
        (if (keyword? read-result)
          (put! event-channel read-result)
          (do
            (doall (map #(% read-result) @read-callbacks-atom))
            (recur))))))
  (read-stream [this]
    (try
      (let [buffer (byte-array buffer-size)
            count (.read (.getInputStream (:socket this)) buffer)]
        (if (= count -1)
          :closed
          (byte-array (take count buffer))))
      (catch Exception e
        (if (= (.getMessage e) "Socket closed")
          :stopped
          :closed))))
  Stoppable
  (stop [_]
    (.close socket)))

(defn- java-net-factory
  ([hostname port]
   (let [socket (Socket. (InetAddress/getByName hostname) port)]
     (.setKeepAlive socket true)
     socket))
  ([^InetSocketAddress socket-address]
   (let [socket (Socket. (.getAddress socket-address) (.getPort socket-address))]
     (.setKeepAlive socket true)
     socket)))

(defn- wrap-socket-watcher!
  "Wrap socket with watcher that handles reconnection returns atom that allows user to
  continue referencing the same object while a new socket is instantiated behind the scenes"
  [retry-interval forever-socket]
  (let [fsocket-atom (atom forever-socket)]
    (go-loop []
      (when (not (= :stopped (<! (:event-channel @fsocket-atom))))
        (let [remote-address (.getRemoteSocketAddress (:socket @fsocket-atom))]
          (println (format "Attempting to reconnect: %s:%s" (.getAddress remote-address) (.getPort remote-address))))
        (when-let [fsocket (try
                             (->ForeverSocket (-> (:socket @fsocket-atom)
                                                  .getRemoteSocketAddress
                                                  java-net-factory)
                                              (:buffer-size @fsocket-atom)
                                              (:read-callbacks-atom @fsocket-atom)
                                              (:event-channel @fsocket-atom))
                             (catch ConnectException _
                               (put! (:event-channel @fsocket-atom) :closed)
                               (<! (timeout retry-interval))
                               false))]
          (let [remote-address (.getRemoteSocketAddress (:socket @fsocket-atom))]
            (println (format "Reconnected to: %s:%s" (.getAddress remote-address) (.getPort remote-address))))
          (reset! fsocket-atom fsocket)
          (start-reader! @fsocket-atom))
        (recur)))
    fsocket-atom))

(defn factory
  "Returns an atom associated with the instantiated ForeverSocket record"
  [hostname port buffer-size retry-interval]
  (let [fsocket (map->ForeverSocket {:socket (java-net-factory hostname port)
                                     :buffer-size buffer-size
                                     :read-callbacks-atom (atom [])
                                     :event-channel (chan)})]
    (start-reader! fsocket)
    (wrap-socket-watcher! retry-interval fsocket)))

(defn str-to-bytes
  "Convert str to bytes"
  [^String input]
  (byte-array (map (comp byte char) input)))

(defn bytes-to-str
  "Convert bytes to str"
  [data]
  (apply str (map char data)))
