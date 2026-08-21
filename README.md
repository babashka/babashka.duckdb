# ffi-duckdb

[DuckDB](https://duckdb.org/) is a database that runs inside your program. It
can read CSV files directly with SQL, without a separate database server.

This library makes DuckDB available to babashka. It returns query results as
Clojure data.

This library is experimental because
[babashka.ffi](https://github.com/babashka/babashka/blob/master/doc/ffi.md) is
experimental.

## Install

The current babashka release does not include `babashka.ffi`. Install the
development build:

```bash
bash <(curl https://raw.githubusercontent.com/babashka/babashka/master/install) --dev-build --dir /tmp --dynamic
```

### macOS

Install the DuckDB library:

```bash
brew install duckdb
```

### Linux

Use the package manager for your Linux version to install `duckdb`.

### Windows

Run these commands in Bash:

```bash
curl -sL -o libduckdb.zip https://github.com/duckdb/duckdb/releases/latest/download/libduckdb-windows-amd64.zip
unzip -o libduckdb.zip duckdb.dll
```

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

### Thread safety

In the DuckDB C API, a connection is a single-thread object. Do not use one
connection from multiple threads. Concurrent use of a shared connection can
crash the process.

Use one connection per thread. The string-path form opens a private connection
for each call.

Opening the same database file twice in one process fails because DuckDB locks
the file. Parallel work against one file is not currently possible with this
library. This is a known limitation.

### HoneySQL

[HoneySQL](https://github.com/seancorfield/honeysql) builds SQL from Clojure
data. Pass the result of `sql/format` to `query`:

```clojure
(require '[honey.sql :as sql])

(duck/query "analytics.db"
  (sql/format {:select [:country :amount]
               :from [:orders]
               :where [:> :amount 30]
               :order-by [[:amount :desc]]}))
;;=> [{:country "NL", :amount 54.0}
;;    {:country "NL", :amount 40.5}
;;    {:country "DE", :amount 31.0}]
```

## Test

Run the tests:

```bash
bb test
```

## License

Copyright (c) Michiel Borkent

Distributed under the MIT License. See LICENSE.
