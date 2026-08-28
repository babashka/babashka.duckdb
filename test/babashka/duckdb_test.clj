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
      (is (= [{:i 1 :d 1.5 :s "a" :b true}
              {:i 2 :d 2.5 :s "b" :b false}
              {:i 3 :d nil :s nil :b nil}]
             (duck/query db "select * from t order by i"))))
    (testing "parameter binding"
      (is (= [{:i 2}]
             (duck/query db ["select i from t where s = ?" "b"])))
      (is (= [{:c 1}]
             (duck/query db ["select count(*) c from t where d > ? and i < ?" 2.0 3]))))
    (testing "temporal, decimal and hugeint values come back typed"
      (is (= [{:d (java.time.LocalDate/parse "2026-08-21")
               :ts (java.time.LocalDateTime/parse "2026-08-21T13:37:00")
               :t (java.time.LocalTime/parse "13:37:00")
               :dc 1.50M
               :h 170141183460469231731687303715884105727N}]
             (duck/query db "select date '2026-08-21' d,
                                    timestamp '2026-08-21 13:37:00' ts,
                                    time '13:37' t,
                                    1.5::decimal(4,2) dc,
                                    170141183460469231731687303715884105727::hugeint h"))))
    (testing "bad sql throws with the duckdb message"
      (is (thrown-with-msg? Exception #"duckdb:"
                            (duck/query db "select nope from nothing"))))
    (testing "a failing prepared statement throws and the connection survives"
      (is (thrown-with-msg? Exception #"duckdb:"
                            (duck/query db ["select nope from nothing where x = ?" 1])))
      (is (= [{:one 1}] (duck/query db "select 1 one"))))))

(deftest path-test
  (testing "a string db opens and closes around the call"
    (let [path (str (System/getProperty "java.io.tmpdir")
                    "/babashka-duckdb-test-" (System/currentTimeMillis) ".db")]
      (duck/execute! path "create table k as select 42 answer")
      (is (= [{:answer 42}] (duck/query path "select * from k"))))))
