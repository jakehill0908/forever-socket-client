(ns forever-socket-client.core-test
  (:require [clojure.test :refer :all]
            [forever-socket-client.core :refer :all]))

(deftest conversions
  (let [test-bytes (byte-array [84 104 105 115 32 105
                                115 32 97 32 115 116
                                114 105 110 103])
        length (count test-bytes)
        test-string "This is a string"]
    (testing "str-to-bytes"
      (is (= (take length test-bytes)
             (take length (str-to-bytes test-string)))))
    (testing "bytes-to-str"
      (is (= test-string (bytes-to-str test-bytes))))))
