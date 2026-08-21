# ffi-duckdb

Run SQL from a babashka script without a separate database server. ffi-duckdb
uses DuckDB and returns query results as Clojure data.

ffi-duckdb is experimental because
[babashka.ffi](https://github.com/babashka/babashka/blob/master/doc/ffi.md) is
experimental.

## Install

Use a version of babashka that includes `babashka.ffi`.

Install the DuckDB library before you use ffi-duckdb. On macOS, run:

```bash
brew install duckdb
```

On Linux, use the package manager for your Linux version to install `duckdb`.

## Usage

This example reads a CSV file with SQL. A CSV file stores rows and columns as
plain text.

DuckDB reads the file directly. You do not need to create a database first.

If you do not want to create a database file, pass `nil` as the first
argument.

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

`query` returns a vector of maps. Each map is one result row. Column names
become Clojure keywords.

SQL `NULL` becomes `nil`.

Use a query vector to keep values separate from the SQL text. Write a `?` for
each value:

```clojure
(duck/query nil ["select * from 'orders.csv' where amount > ?" 30])
```

You can also pass query vectors from
[HoneySQL](https://github.com/seancorfield/honeysql) without changes.

Use `with-db` to run several operations with the same database. Give it a file
name to save the database:

```clojure
(duck/with-db [db "analytics.db"]
  (duck/execute! db "create table orders as select * from 'orders.csv'")
  (duck/query db "select sum(amount) total from orders"))
;;=> [{:total 181.5}]
```

`with-db` closes the database after the code inside it finishes.

Use `execute!` for SQL that changes the database. It returns
`{:rows-changed n}`, where `n` is the number of changed rows.

## Test

Run the tests:

```bash
bb test
```

## License

Copyright (c) Michiel Borkent

Distributed under the MIT License. See LICENSE.
