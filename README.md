# ffi-duckdb

DuckDB for babashka over [babashka.ffi](https://github.com/babashka/babashka/blob/master/doc/ffi.md).

Experimental, like babashka.ffi itself. Needs a babashka with `babashka.ffi`
and the DuckDB shared library (`brew install duckdb`, or the duckdb package
of your distro).

## Usage

```clojure
(require '[babashka.duckdb :as duck])

(duck/query "/tmp/analytics.db"
  ["select * from 'events.csv' where user = ?" "michiel"])
;;=> [{:user "michiel" :event "login"}]
```

A string db argument opens and closes the database around the call. Pass
nil as path for an in-memory database. Hold a connection for multiple
operations:

```clojure
(duck/with-db [db nil]
  (duck/execute! db ["create table t as select * from 'data.parquet'"])
  (duck/query db "select count(*) c from t"))
```

`query` returns a vector of maps with keywordized column names. `execute!`
returns `{:rows-changed n}`. Query vectors follow the `[sql & params]`
shape, so [honeysql](https://github.com/seancorfield/honeysql)-formatted
vectors work as-is.

## Test

```bash
bb test
```

## License

Copyright (c) Michiel Borkent

Distributed under the EPL License. See LICENSE.
