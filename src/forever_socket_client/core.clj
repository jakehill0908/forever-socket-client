(ns forever-socket-client.core
  (:import [java.net Socket InetAddress InetSocketAddress SocketException ConnectException])
  (:require [clojure.core.async :refer [put! <! chan go-loop timeout]]))

(defprotocol IO
  "IO protocols for ForeverSocket record"
  (append-callback [this cb])
  (start-reader! [this])
  (read-stream [this])
  (write [this data]))

(defprotocol Stoppable
  "Implements stop function"
  (stop [this]))

(defrecord ForeverSocket
  [socket buffer-size read-callbacks-atom event-channel]
  IO
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
          (put! event-channel read-result) ; Put event on channel and exit reader loop
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
      (catch SocketException e
        (if (= (.getMessage e) "Socket closed")
          :stopped
          :closed))))
  Stoppable
  (stop [_]
    (.close socket)))

(defn java-net-factory
  ([hostname port]
   (let [socket (Socket. (InetAddress/getByName hostname) port)]
     (.setKeepAlive socket true)
     socket))
  ([^InetSocketAddress socket-address]
   (let [socket (Socket. (.getAddress socket-address) (.getPort socket-address))]
     (.setKeepAlive socket true)
     socket)))

(defn wrap-socket-watcher!
  "Wrap socket with watcher"
  [retry-interval forever-socket]
  (let [fsocket-atom (atom forever-socket)]
    (go-loop []
      (when (not (= :stopped (<! (:event-channel @fsocket-atom))))
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
