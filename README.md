# Caching Proxy Server

A multithreaded HTTP caching proxy server built in Java.

This project was built as a hands-on backend/systems project to understand HTTP communication, proxy servers, caching, TTL, LRU eviction, and concurrency.

The project idea was taken from the [roadmap.sh Caching Server project](https://roadmap.sh/projects/caching-server).

---

## Project Overview

The caching proxy server sits between a client and an origin server.

Instead of the client communicating directly with the origin server, requests are first sent to the proxy.

```text
Client
   |
   | HTTP Request
   v
Caching Proxy
   |
   |-- Cache HIT  ------> Return cached response
   |
   |-- Cache MISS
   v
Origin Server
   |
   | Response
   v
Caching Proxy
   |
   | Store response
   v
Client
```

For example, if the proxy is configured with:

```text
Origin: https://api.dictionaryapi.dev
Port: 8080
```

and the client requests:

```text
http://localhost:8080/api/v2/entries/en/time
```

the proxy forwards the request to:

```text
https://api.dictionaryapi.dev/api/v2/entries/en/time
```

The response is returned to the client and stored in the cache.

If the same request is made again while the cached entry is valid, the proxy returns the cached response instead of contacting the origin server.

---

## Features

### Core Features

* HTTP reverse proxy functionality
* Configurable origin server
* Configurable proxy port
* In-memory response caching
* Cache HIT/MISS detection
* `X-Cache` response header
* TTL-based cache expiration
* LRU cache eviction
* Multithreaded request handling
* Concurrent client support
* Response body and headers caching
* Maximum cache size limitation
* CLI argument validation
* CLI help/usage guide

### Additional Features

The original [roadmap.sh Caching Server](https://roadmap.sh/projects/caching-server) project focuses on the basic caching proxy functionality. I extended the implementation with several additional features.

#### TTL-based expiration

Cached responses are not stored indefinitely.

Each cache entry stores a timestamp:

```text
Cache Entry
├── Response
├── Headers
├── Status Code
└── Timestamp
```

When a cached response is requested, the proxy checks whether the entry has exceeded the configured TTL.

The current TTL is:

```text
60 seconds
```

If the entry has expired, it is removed and the request is forwarded to the origin server.

---

#### LRU eviction

The cache has a maximum number of entries.

The current limit is:

```text
100 entries
```

When the cache exceeds this limit, the least recently used entry is removed.

The LRU implementation uses:

```text
ConcurrentHashMap
       +
Doubly Linked List
```

The `ConcurrentHashMap` provides efficient cache lookups, while the doubly linked list maintains the usage order.

The most recently accessed entry is moved to the front of the list, while the least recently used entry remains at the tail.

The main LRU operations are approximately:

```text
O(1)
```

---

#### Multithreading

The proxy uses a fixed thread pool to handle multiple clients concurrently.

```java
Executors.newFixedThreadPool(10)
```

When a client connects, its request is submitted to the thread pool instead of blocking the main server loop.

Conceptually:

```text
                    Proxy Server
                         |
             +-----------+-----------+
             |           |           |
          Thread 1    Thread 2    Thread 3
             |           |           |
          Client A    Client B    Client C
```

---

#### Cache status header

The proxy adds an `X-Cache` header to responses.

For a response served from the cache:

```http
X-Cache: HIT
```

For a response retrieved from the origin server:

```http
X-Cache: MISS
```

This makes it easy to verify cache behavior using a browser, HTTP client, or testing tool.

---

#### Response headers

The proxy stores the origin response along with its relevant response headers instead of caching only the response body.

This allows cached responses to preserve headers such as:

```text
Content-Type
Date
ETag
Cache-Control
```

while avoiding inappropriate forwarding of hop-by-hop headers.

---

## Architecture

The project consists of three main components:

```text
                    +-------------------+
                    |      Client       |
                    +---------+---------+
                              |
                              | HTTP Request
                              v
                    +---------+---------+
                    |   Proxy Server    |
                    |                   |
                    | Request Handling  |
                    |       |           |
                    |       v           |
                    |  Cache Lookup     |
                    +----+---------+----+
                         |         |
                    HIT  |         | MISS
                         |         |
                         |         v
                         |   +-----+------+
                         |   |   Origin   |
                         |   |   Server   |
                         |   +-----+------+
                         |         |
                         |         | Response
                         |         v
                         |   +-----+------+
                         |   |   Cache    |
                         |   +------------+
                         |
                         v
                    +----+----+
                    | Client  |
                    +---------+
```

### Request Flow

#### 1. Client sends a request

For example:

```http
GET /api/v2/entries/en/time HTTP/1.1
```

to:

```text
http://localhost:8080
```

#### 2. Proxy constructs the origin URL

If the configured origin is:

```text
https://api.dictionaryapi.dev
```

the proxy creates:

```text
https://api.dictionaryapi.dev/api/v2/entries/en/time
```

#### 3. Cache lookup

A cache key is generated using the HTTP method and target URL:

```text
GET:https://api.dictionaryapi.dev/api/v2/entries/en/time
```

The proxy checks the cache for this key.

#### 4. Cache HIT

If the entry exists and its TTL has not expired:

```text
CACHE HIT
```

The proxy:

1. Moves the entry to the front of the LRU list.
2. Returns the cached response.
3. Adds `X-Cache: HIT`.

No request is sent to the origin.

#### 5. Cache MISS

If the entry does not exist or has expired:

```text
CACHE MISS
```

The proxy:

1. Sends the request to the origin server.
2. Receives the response.
3. Stores the response in the cache.
4. Returns the response to the client.
5. Adds `X-Cache: MISS`.

---

## Cache Design

The cache currently uses:

```text
ConcurrentHashMap<String, Node>
```

combined with a doubly linked list.

Each node contains:

```text
Node
├── key
├── CacheEntry
├── previous
└── next
```

The `CacheEntry` contains:

```text
CacheEntry
├── status code
├── response body
├── response headers
└── timestamp
```

The linked list is maintained in the following order:

```text
HEAD
 |
 v
Most Recently Used
 |
 v
...
 |
 v
Least Recently Used
 |
 v
TAIL
```

Whenever an entry is accessed, it is moved to the front.

When the cache exceeds its maximum size, the entry at the tail is removed.

---

## TTL

Each cached response contains a timestamp representing when it was added to the cache.

The proxy compares:

```text
Current Time - Cache Entry Timestamp
```

against the configured TTL.

Currently:

```text
CACHE_EXPIRY = 60 seconds
```

For example:

```text
Request 1
   |
   +--> MISS
   |
   +--> Store response
          timestamp = T0

Request 2 at T0 + 10 sec
   |
   +--> HIT

Request 3 at T0 + 61 sec
   |
   +--> Expired
   |
   +--> MISS
   |
   +--> Request origin again
```

---

## Concurrency

The proxy uses a fixed thread pool:

```java
Executors.newFixedThreadPool(10)
```

The main server thread accepts incoming connections and delegates request processing to worker threads.

The cache uses:

```text
ConcurrentHashMap
```

while modifications to the LRU linked list are protected using synchronization.

This provides a simple thread-safe design while keeping cache lookups efficient.

---

## Running the Project

Currently, the project runs directly using Java commands. It does not yet provide a standalone executable or globally installed `caching-proxy` command.

### Requirements

* Java 11 or later
* Terminal or command prompt
* Internet access to reach the configured origin server

### Compile

From the project directory:

```bash
javac Cache.java
```

### Start the proxy

The proxy can be started with:

```bash
java Cache --port <port> --origin <origin-url>
```

For example:

```bash
java Cache --port 8080 --origin https://api.dictionaryapi.dev
```

The proxy will start on:

```text
localhost:8080
```

and forward requests to:

```text
https://api.dictionaryapi.dev
```

### Default Port

If `--port` is not provided, the default port is:

```text
3000
```

For example:

```bash
java Cache --origin https://api.dictionaryapi.dev
```

starts the proxy on:

```text
localhost:3000
```

---

## Testing the Proxy

Once the proxy is running, send a request through it.

For example:

```text
http://localhost:8080/api/v2/entries/en/time
```

The proxy forwards this to:

```text
https://api.dictionaryapi.dev/api/v2/entries/en/time
```

The first request should return:

```http
X-Cache: MISS
```

A subsequent request for the same resource should return:

```http
X-Cache: HIT
```

---

## Load Testing

A separate Java test client is included to test the proxy under concurrent load.

The test client performs operations such as:

* Cache warm-up
* Concurrent requests
* Cache HIT/MISS measurement
* Response time measurement
* TTL testing
* LRU testing

The load tester uses multiple threads and records:

```text
Successful Requests
Failed Requests
Cache Hits
Cache Misses
Hit Rate
Average Response Time
Minimum Response Time
Maximum Response Time
Total Response Time
Wall Clock Time
```

Example output:

```text
======================================
             RESULTS
======================================
Successful Requests : 38
Failed Requests     : 0

Cache Hits          : 20
Cache Misses        : 18

Hit Rate            : 52.63 %

Average Response    : 42.31 ms
Minimum Response    : 2 ms
Maximum Response    : 183 ms

Total Response Time : 1608 ms

Wall Clock Time     : 512 ms
======================================
```

---

## CLI Commands

### Start the proxy

```bash
java Cache --port 8080 --origin https://api.dictionaryapi.dev
```

### Start using the default port

```bash
java Cache --origin https://api.dictionaryapi.dev
```

### Display help

```bash
java Cache --help
```

### Clear cache

```bash
java Cache --clear-cache
```

> **Note:** The `--clear-cache` functionality is currently incomplete when the proxy is already running. See the limitations section.

---

## Limitations

This project is currently a learning project and is not intended to be production-ready.

### 1. Cache is not persistent

The cache currently exists only in memory.

```text
Proxy Running
     |
     v
    RAM
     |
     +--> Cached responses
```

If the proxy is stopped or crashes, all cached responses are lost.

A future implementation could use persistent storage such as:

* SQLite
* Redis
* RocksDB
* File-based storage

This would allow cached responses to survive server restarts.

---

### 2. `--clear-cache` is not fully implemented

The intended command is:

```bash
java Cache --clear-cache
```

However, the cache lives inside the memory of the currently running proxy JVM.

Running another Java process does not give that process access to the existing proxy's cache.

Conceptually:

```text
Running Proxy JVM
      |
      +--> Cache A


New JVM
      |
      +--> Cache B
```

The second JVM cannot directly clear Cache A.

A future implementation could introduce an administrative endpoint or IPC mechanism.

For example:

```text
POST /admin/cache/clear
```

The CLI command could then communicate with the running proxy and request that it clear its cache.

---

### 3. Cache stampede

The current implementation can potentially send multiple requests to the origin when several clients request the same uncached resource at the same time.

For example:

```text
Thread 1 ──> Cache MISS ──> Origin
Thread 2 ──> Cache MISS ──> Origin
Thread 3 ──> Cache MISS ──> Origin
Thread 4 ──> Cache MISS ──> Origin
```

even though all requests are for the same resource.

A possible improvement would be request coalescing or a single-flight mechanism so that only one origin request is made while other threads wait for the result.

---

### 4. Limited HTTP functionality

The current implementation primarily handles `GET` requests.

It does not currently implement a complete general-purpose HTTP proxy supporting every HTTP method and request body.

Future versions could add support for:

```text
POST
PUT
PATCH
DELETE
HEAD
OPTIONS
```

where appropriate.

Caching should remain restricted to requests that are safe and appropriate to cache.

---

### 5. Limited HTTP cache semantics

The proxy currently uses a fixed application-level TTL:

```text
60 seconds
```

It does not yet fully implement HTTP caching semantics such as:

```text
Cache-Control
Expires
ETag
If-None-Match
Last-Modified
If-Modified-Since
```

A future implementation could use these headers to make caching behavior more consistent with HTTP caching standards.

---

### 6. Configuration

The origin, port, cache size, and TTL are currently configured through the application and command-line arguments.

A future version could support a configuration file such as:

```text
config.properties
```

or:

```text
config.yaml
```

---

### 7. Observability

The current implementation primarily uses console logging.

A production-oriented implementation could add:

* Structured logging
* Request IDs
* Cache metrics
* Prometheus metrics
* Health checks
* Request latency monitoring
* Cache size monitoring

---

## Future Improvements

Potential future improvements include:

* Persistent cache storage
* Fully working remote cache clearing
* Request coalescing
* HTTP `Cache-Control` support
* ETag-based cache validation
* Conditional requests
* Support for additional HTTP methods
* Configurable TTL
* Configurable cache size
* Configurable worker thread count
* Improved error handling
* Graceful shutdown
* Structured logging
* Metrics and monitoring
* Docker support
* More comprehensive unit and integration tests
* Standalone executable packaging
* Installable CLI command
* Improved HTTPS support
* Streaming support for large responses

---

## Project Status

**Status: Working / Learning Project**

The core caching proxy functionality is implemented, including:

* Configurable origin
* Configurable port
* HTTP request forwarding
* Response caching
* Cache HIT/MISS detection
* TTL expiration
* LRU eviction
* Multithreaded request handling
* Concurrent load testing

One of the main design decisions was implementing the LRU cache using a `ConcurrentHashMap` combined with a doubly linked list. This provides approximately `O(1)` cache lookup and LRU operations while allowing the cache to be used across multiple worker threads.
There are still several areas that could be improved before considering the project production-ready, particularly persistent storage, complete cache invalidation through the CLI, more robust HTTP caching semantics, and improved handling of simultaneous cache misses.

---

## Project Reference

This project was inspired by the Caching Server project from roadmap.sh:

https://roadmap.sh/projects/caching-server
