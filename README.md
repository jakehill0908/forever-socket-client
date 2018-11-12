# forever-socket-client

An extremely simple Clojure library that creates asynchronous socket clients that try to stay connected forever.

Uses only standard java.net Sockets and core.async

## Usage

```clojure
(ns my-namespace
  (:require [forever-socket-client.core :refer [socket str-to-bytes bytes-to-str]]
            [clojure.core.async :refer [chan <!!]])) ; Import

(def mysock (socket "localhost" 3000 2048 10000)) ; Connect to socket (hostname port buffer-size retry-interval)
(def mysock2 (socket "localhost" 3000))           ; Connect with default buffer and retry interval (2048 bytes and 5 seconds)

(write-to-socket mysock (str-to-bytes "Hello Server!")) ; Write byte-array to socket

(setup-read-callback mysock (fn [data] ; Only one callback can be assigned per socket
                                (println (bytes-to-str data)))) ; Setup callback function for received bytes
```
