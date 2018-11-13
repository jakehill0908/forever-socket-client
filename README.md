# forever-socket-client

An extremely simple Clojure library that creates asynchronous socket clients that try to stay connected forever.

Uses only standard java.net Sockets and core.async

`[forever-socket-client "1.1.0"]`

## Usage

```clojure
(ns my-namespace
  (:require [forever-socket-client.core :refer [socket str-to-bytes bytes-to-str write-to-socket setup-read-callback]])) ; Import

(def mysock (socket "localhost" 3000 2048 10000)) ; Connect to socket (hostname port buffer-size retry-interval)
(def mysock2 (socket "localhost" 3000))           ; Connect with default buffer and retry interval (2048 bytes and 5 seconds)

(write-to-socket mysock (str-to-bytes "Hello Server!")) ; Write byte-array to socket

(setup-read-callback mysock (fn [data] ; Only one callback can be assigned per socket
                                (println (bytes-to-str data)))) ; Setup callback function for received bytes

((:stop @mysock)) ; Shutdown async processes and close socket connection
```
