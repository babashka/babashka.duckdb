(ns babashka.duckdb-test
  (:require [babashka.duckdb :as duck]
            [clojure.test :refer [deftest is testing]]))

(deftest version-test
  (is (re-find #"^v?\d" (duck/version))))

(deftest query-test
  (duck/with-db [db nil]
    (testing "ddl and insert report rows changed"
      (duck/execute! db "create table t (i bigint, d double, s varchar, b boolean)")
      (is (= {:rows-changed 3}
             (duck/execute! db "insert into t values (1, 1.5, 'a', true), (2, 2.5, 'b', false), (3, null, null, null)"))))
    (testing "typed reads and null"
      (is (= [{:i 1 :d 1.5 :s "a" :b 1}
              {:i 2 :d 2.5 :s "b" :b 0}
              {:i 3 :d nil :s nil :b nil}]
             (duck/query db "select * from t order by i"))))
    (testing "parameter binding"
      (is (= [{:i 2}]
             (duck/query db ["select i from t where s = ?" "b"])))
      (is (= [{:c 1}]
             (duck/query db ["select count(*) c from t where d > ? and i < ?" 2.0 3]))))
    (testing "bad sql throws with the duckdb message"
      (is (thrown-with-msg? Exception #"duckdb:"
                            (duck/query db "select nope from nothing"))))))

(deftest path-test
  (testing "a string db opens and closes around the call"
    (let [path (str (System/getProperty "java.io.tmpdir")
                    "/ffi-duckdb-test-" (System/currentTimeMillis) ".db")]
      (duck/execute! path "create table k as select 42 answer")
      (is (= [{:answer 42}] (duck/query path "select * from k"))))))
