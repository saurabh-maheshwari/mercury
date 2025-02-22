# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---
## Version 1.12.7, 7/15/2019

### Added

1. Periodic routing table integrity check (15 minutes)
2. Set kafka read pointer to the beginning for new application instances except presence monitor
3. REST automation helper application in the "extensions" project
4. Support service discovery of multiple routes in the updated PostOffice's exists() method
5. logback to set log level based on environment variable LOG_LEVEL (default is INFO)

### Removed

N/A

### Changed

Minor refactoring of kafka-connector and hazelcast-connector to ensure that they can coexist if you want to include both of these dependencies in your project.
This is for convenience of dev and testing. In production, please select only one cloud connector library to reduce memory footprint.

---

## Version 1.12.4, 6/24/2019

### Added

Add inactivity expiry timer to ObjectStreamIO so that house-keeper can clean up resources that are idle

### Removed

N/A

### Changed

1. Disable HTML encape sequence for GSON serializer
2. Bug fix for GSON serialization optimization
3. Bug fix for Object Stream housekeeper

By default, GSON serializer converts all numbers to double, resulting in unwanted decimal point for integer and long.
To handle custom map serialization for correct representation of numbers, an unintended side effect was introduced in earlier releases.
List of inner PoJo would be incorrectly serialized as map, resulting in casting exception. This release resolves this issue.

---

## Version 1.12.1, 6/10/2019

### Added

1. Store-n-forward pub/sub API will be automatically enabled if the underlying cloud connector supports it. e.g. kafka
2. ObjectStreamIO, a convenient wrapper class, to provide event stream I/O API.
3. Object stream feature is now a standard feature instead of optional.
4. Deferred delivery added to language connector.

### Removed

N/A

### Changed

N/A

---

## Version 1.11.40, 5/25/2019

### Added

1. Route substitution for simple versioning use case
2. Add "Strict Transport Security" header if HTTPS (https://tools.ietf.org/html/rfc6797)
3. Event stream connector for Kafka
4. Distributed housekeeper feature for Hazelcast connector

### Removed

System log service

### Changed

Refactoring of Hazelcast event stream connector library to sync up with the new Kafka connector.

---

## Version 1.11.39, 4/30/2019

### Added

Language-support service application for Python, Node.js and Go, etc.
Python language pack project is available at https://github.com/Accenture/mercury-python

### Removed

N/A

### Changed

1. replace Jackson serialization engine with Gson (`platform-core` project)
2. replace Apache HttpClient with Google Http Client (`rest-spring`)
3. remove Jackson dependencies from Spring Boot (`rest-spring`)
4. interceptor improvement

---

## Version 1.11.33, 3/25/2019

### Added

N/A

### Removed

N/A

### Changed

1. Move safe.data.models validation rules from EventEnvelope to SimpleMapper
2. Apache fluent HTTP client downgraded to version 4.5.6 because the pom file in 4.5.7 is invalid

---

## Version 1.11.30, 3/7/2019

### Added

Added retry logic in persistent queue when OS cannot update local file metadata in real-time for Windows based machine.

### Removed

N/A

### Changed

pom.xml changes - update with latest 3rd party open sources dependencies. 

---

## Version 1.11.29, 1/25/2019

### Added

`platform-core`

1. Support for long running functions so that any long queries will not block the rest of the system.
2. "safe.data.models" is available as an option in the application.properties. This is an additional security measure to protect against Jackson deserialization vulnerability. See example below:

```
#
# additional security to protect against model injection
# comma separated list of model packages that are considered safe to be used for object deserialization
#
#safe.data.models=com.accenture.models
```

`rest-spring`

"/env" endpoint is added. See sample application.properties below:

```
#
# environment and system properties to be exposed to the "/env" admin endpoint
#
show.env.variables=USER, TEST
show.application.properties=server.port, cloud.connector
```

### Removed

N/A

### Changed

`platform-core`

Use Java Future and an elastic cached thread pool for executing user functions.

### Fixed

N/A

---


## Version 1.11.28, 12/20/2018

### Added

Hazelcast support is added. This includes two projects (hazelcast-connector and hazelcast-presence).

Hazelcast-connector is a cloud connector library. Hazelcast-presence is the "Presence Monitor" for monitoring the presence status of each application instance.

### Removed

`platform-core`

The "fixed resource manager" feature is removed because the same outcome can be achieved at the application level. e.g. The application can broadcast requests to multiple application instances with the same route name and use a callback function to receive response asynchronously. The services can provide resource metrics so that the caller can decide which is the most available instance to contact. 

For simplicity, resources management is better left to the cloud platform or the application itself.

### Changed

N/A

### Fixed

N/A
