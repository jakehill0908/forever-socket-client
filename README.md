# forever-socket-client

An extremely simple Clojure library that creates asynchronous socket clients that try to stay connected forever.

Uses only standard java.net Sockets and core.async

`[forever-socket-client "1.2.0"]`

## Usage

```clojure
(ns your-namespace.core
  (:require [forever-socket-client.core :as fsc])) ; Import

; Factory returns an atom
(def socket (fsc/factory "localhost" ; Hostname
                         8080        ; Port
                         2048        ; Max buffer size
                         1000))      ; Reconnect (retry) interval milliseconds
 
(fsc/write @socket (fsc/str-to-bytes "Hello World!"))           ; Write to socket
(fsc/append-callback @socket #(printf "Received data: %s\n" %)) ; Receive data from socket async
(fsc/append-callback @socket (fn [data]                         ; Multiple callbacks may be assigned
                               (printf "Received %d Bytes\n" (count data))))
                         
(fsc/stop @socket) ; Shutdown async processes and close socket connection gracefully
```
