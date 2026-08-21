(ns babashka.duckdb
  "Run SQL with DuckDB from babashka:

      (require '[babashka.duckdb :as duck])
      (duck/query nil \"select 42 as answer\")

  If you do not want to create a database file, pass nil as the database.
  Pass a file name to save data between calls.

  Use with-db to share a temporary database across several calls:

      (duck/with-db [db nil]
        (duck/execute! db \"create table t as select 42 as answer\")
        (duck/query db \"select * from t\"))

  Query results contain one map for each row. Column names are keywords.
  SQL NULL values become nil."
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [clojure.string :as str]))

(ffi/load-system-library "duckdb")

(defcfn ^:private c-library-version "duckdb_library_version" [] :string)
(defcfn ^:private c-open "duckdb_open" [:string :pointer] :int)
(defcfn ^:private c-close "duckdb_close" [:pointer] :void)
(defcfn ^:private c-connect "duckdb_connect" [:pointer :pointer] :int)
(defcfn ^:private c-disconnect "duckdb_disconnect" [:pointer] :void)
(defcfn ^:private c-query "duckdb_query" [:pointer :string :pointer] :int)
(defcfn ^:private c-destroy-result "duckdb_destroy_result" [:pointer] :void)
(defcfn ^:private c-result-error "duckdb_result_error" [:pointer] :string)
(defcfn ^:private c-column-count "duckdb_column_count" [:pointer] :uint64)
(defcfn ^:private c-row-count "duckdb_row_count" [:pointer] :uint64)
(defcfn ^:private c-rows-changed "duckdb_rows_changed" [:pointer] :uint64)
(defcfn ^:private c-column-name "duckdb_column_name" [:pointer :uint64] :string)
(defcfn ^:private c-column-type "duckdb_column_type" [:pointer :uint64] :int)
(defcfn ^:private c-value-is-null "duckdb_value_is_null" [:pointer :uint64 :uint64] :uint8)
(defcfn ^:private c-value-int64 "duckdb_value_int64" [:pointer :uint64 :uint64] :int64)
(defcfn ^:private c-value-double "duckdb_value_double" [:pointer :uint64 :uint64] :double)
(defcfn ^:private c-value-varchar "duckdb_value_varchar" [:pointer :uint64 :uint64] :pointer)
(defcfn ^:private c-duckdb-free "duckdb_free" [:pointer] :void)
(defcfn ^:private c-prepare "duckdb_prepare" [:pointer :string :pointer] :int)
(defcfn ^:private c-destroy-prepare "duckdb_destroy_prepare" [:pointer] :void)
(defcfn ^:private c-prepare-error "duckdb_prepare_error" [:pointer] :string)
(defcfn ^:private c-execute-prepared "duckdb_execute_prepared" [:pointer :pointer] :int)
(defcfn ^:private c-bind-int64 "duckdb_bind_int64" [:pointer :uint64 :int64] :int)
(defcfn ^:private c-bind-double "duckdb_bind_double" [:pointer :uint64 :double] :int)
(defcfn ^:private c-bind-varchar "duckdb_bind_varchar" [:pointer :uint64 :string] :int)
(defcfn ^:private c-bind-null "duckdb_bind_null" [:pointer :uint64] :int)

(defn version
  "Returns the version of the loaded DuckDB library."
  []
  (c-library-version))

;; Reserve more than the 48 bytes that duckdb_result needs.
(def ^:private result-size 64)

(defn open
  "Opens a DuckDB connection. A nil path creates a temporary database for this
  connection. A file name opens or creates that database file. Returns a
  connection for use with query, execute! and close!."
  [path]
  (let [pdb (ffi/alloc (ffi/sizeof :pointer))
        pconn (ffi/alloc (ffi/sizeof :pointer))]
    (try
      (when-not (zero? (c-open path pdb))
        (throw (ex-info (str "duckdb: cannot open " path) {})))
      (let [db (ffi/read pdb :pointer)]
        (when-not (zero? (c-connect db pconn))
          (c-close pdb)
          (throw (ex-info "duckdb: cannot connect" {})))
        {:db pdb :conn (ffi/read pconn :pointer)})
      (finally (ffi/free pconn)))))

(defn close!
  "Closes a connection from open. Returns nil."
  [{:keys [db conn]}]
  (let [pconn (ffi/alloc (ffi/sizeof :pointer))]
    (try
      (ffi/write pconn :pointer 0 conn)
      (c-disconnect pconn)
      (c-close db)
      (finally
        (ffi/free pconn)
        (ffi/free db))))
  nil)

(defmacro with-db
  "Opens a database for the enclosed code. Closes the database after the code
  finishes. Use nil for a temporary database.

      (with-db [db \"analytics.db\"]
        (query db \"select 42 as answer\"))"
  [[sym path] & body]
  `(let [~sym (open ~path)]
     (try ~@body
          (finally (close! ~sym)))))

(defn- varchar-at [res col row]
  (let [p (c-value-varchar res col row)]
    (when-not (ffi/null? p)
      (let [s (ffi/ptr->string p)]
        (c-duckdb-free p)
        s))))

(defn- value-at [res col row type-id]
  (when (zero? (c-value-is-null res col row))
    (cond
      ;; BOOLEAN
      (= 1 type-id) (not (zero? (c-value-int64 res col row)))
      ;; TINYINT..UBIGINT
      (<= 2 type-id 9) (c-value-int64 res col row)
      ;; FLOAT, DOUBLE
      (<= 10 type-id 11) (c-value-double res col row)
      ;; TIMESTAMP: "2026-08-21 13:00:00[.ffffff]"
      (= 12 type-id) (java.time.LocalDateTime/parse
                      (str/replace-first (varchar-at res col row) " " "T"))
      ;; DATE
      (= 13 type-id) (java.time.LocalDate/parse (varchar-at res col row))
      ;; TIME
      (= 14 type-id) (java.time.LocalTime/parse (varchar-at res col row))
      ;; HUGEINT (int128)
      (= 16 type-id) (bigint (java.math.BigInteger. ^String (varchar-at res col row)))
      ;; DECIMAL
      (= 19 type-id) (bigdec (varchar-at res col row))
      :else (varchar-at res col row))))

(defn- read-rows [res]
  (let [ncol (c-column-count res)
        cols (mapv (fn [c] {:name (keyword (c-column-name res c))
                            :type (c-column-type res c)})
                   (range ncol))
        nrow (c-row-count res)]
    (mapv (fn [r]
            (into {} (map-indexed
                      (fn [c {:keys [name type]}]
                        [name (value-at res c r type)])
                      cols)))
          (range nrow))))

(defn- run* [conn q collect-rows?]
  (let [[sql & params] (if (string? q) [q] q)
        res (ffi/alloc result-size)]
    (try
      (if (seq params)
        (let [pstmt (ffi/alloc (ffi/sizeof :pointer))]
          (try
            ;; on failure the finally destroys the statement; the error
            ;; message must be read from it first
            (when-not (zero? (c-prepare (:conn conn) sql pstmt))
              (let [stmt (ffi/read pstmt :pointer)]
                (throw (ex-info (str "duckdb: " (c-prepare-error stmt))
                                {:sql sql}))))
            (let [stmt (ffi/read pstmt :pointer)]
              (doseq [[i v] (map-indexed vector params)]
                (let [i (inc i)
                      rc (cond
                           (nil? v) (c-bind-null stmt i)
                           (integer? v) (c-bind-int64 stmt i v)
                           (float? v) (c-bind-double stmt i v)
                           (string? v) (c-bind-varchar stmt i v)
                           (boolean? v) (c-bind-int64 stmt i (if v 1 0))
                           :else (throw (ex-info (str "duckdb: cannot bind " (type v))
                                                 {:value v})))]
                  (when-not (zero? rc)
                    (throw (ex-info "duckdb: bind failed" {:sql sql :param v})))))
              (when-not (zero? (c-execute-prepared stmt res))
                (throw (ex-info (str "duckdb: " (c-result-error res)) {:sql sql}))))
            (finally
              (c-destroy-prepare pstmt)
              (ffi/free pstmt))))
        (when-not (zero? (c-query (:conn conn) sql res))
          (throw (ex-info (str "duckdb: " (c-result-error res)) {:sql sql}))))
      (if collect-rows?
        (read-rows res)
        {:rows-changed (c-rows-changed res)})
      (finally
        (c-destroy-result res)
        (ffi/free res)))))

(defn- with-conn [db-or-path f]
  (if (map? db-or-path)
    (f db-or-path)
    (with-db [db db-or-path] (f db))))

(defn query
  "Runs a query and returns a vector of maps. Each map is one result row.
  Column names are keywords. SQL NULL values become nil.

  db can be a connection from open, a database file name, or nil. q can be a
  SQL string or a vector. The vector starts with SQL. Each ? in SQL uses the
  next value in the vector."
  [db q]
  (with-conn db (fn [db] (run* db q true))))

(defn execute!
  "Runs SQL that changes the database. db and q accept the same values as
  query. Returns {:rows-changed n}."
  [db q]
  (with-conn db (fn [db] (run* db q false))))
