# ffi-duckdb

DuckDB for babashka over [babashka.ffi](https://github.com/babashka/babashka/blob/master/doc/ffi.md).

Experimental, like babashka.ffi itself. Needs a babashka with `babashka.ffi`
and the DuckDB shared library (`brew install duckdb`, or the duckdb package
of your distro).

## Usage

DuckDB queries data files directly, so a babashka script gets SQL
analytics over CSV, Parquet and JSON without creating a database. Pass
nil as the db to run in memory:

```clojure
(require '[babashka.duckdb :as duck])

(spit "orders.csv" "country,amount
NL,25.0
NL,54.0
DE,19.0
NL,40.5
DE,31.0
FR,12.0")

(duck/query nil "select country, count(*) n, round(avg(amount), 1) avg
                 from 'orders.csv'
                 group by country order by n desc")
;;=> [{:country "NL", :n 3, :avg 39.8}
;;    {:country "DE", :n 2, :avg 25.0}
;;    {:country "FR", :n 1, :avg 12.0}]
```

The same works for `'logs/*.parquet'` globs, `read_json('api.json')`,
and joins across files of different formats.

`query` returns a vector of maps with keywordized column names. Rows come
back typed: longs, doubles, booleans, java.time values for DATE and
TIMESTAMP, BigDecimal for DECIMAL, nil for NULL.

Query vectors follow the `[sql & params]` shape, so
[honeysql](https://github.com/seancorfield/honeysql)-formatted vectors
work as-is:

```clojure
(duck/query nil ["select * from 'orders.csv' where amount > ?" 30])
```

A string db argument opens that database file and closes it around the
call. To keep state across calls, hold a connection:

```clojure
(duck/with-db [db "analytics.db"]
  (duck/execute! db "create table orders as select * from 'orders.csv'")
  (duck/query db "select sum(amount) total from orders"))
;;=> [{:total 181.5}]
```

`execute!` returns `{:rows-changed n}`.

## Test

```bash
bb test
```

## License

Copyright (c) Michiel Borkent

Distributed under the MIT License. See LICENSE.
