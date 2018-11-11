# forever-socket-client

A Clojure library that creates socket clients that don't disconnect.  Use at your own risk.

## Usage

Creating a socket
```clojure
(ns my-namespace
  (:require [forever-socket-client.core :refer [socket str-to-bytes bytes-to-str]])) ; Import
    
(def mysock (socket "localhost" 3000 2048 10000)) ; Connect to socket (hostname port buffer-size retry-interval)
(def mysock2 (socket "localhost" 3000))           ; Connect with default buffer and retry interval (2048 bytes and 5 seconds)

(write-to-socket mysock (str-to-bytes "Hello Server!")) ; Write byte-array to socket

(setup-read-hook mysock (fn [data]
                            (println (bytes-to-str data)))) ; Setup callback function for received bytes (receiver thread cannot be stopped and only one hook can be created)
```
