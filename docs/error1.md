# MiniDrive Phase 1 — Error 1: Spring Boot + PostgreSQL Setup

## Overview

This document records the complete debugging journey while completing **MiniDrive Phase 1**.

Phase 1 goal:

- Spring Boot application runs
- PostgreSQL runs through Docker Compose
- Spring Boot connects to PostgreSQL
- JPA/Hibernate initializes correctly
- `MiniDriveApplicationTests.contextLoads` passes against the real PostgreSQL database
- `/api/health` responds successfully

The project architecture is intentionally phased. Later features such as authentication, MinIO, Redis, sharing, versioning, resumable uploads, deduplication, and distributed storage were not implemented as part of this fix.

---

# 1. Initial Problem

The first attempt to run Maven failed because `backend/pom.xml` was malformed.

Maven reported:

```text
Malformed POM C:\MiniDrive\backend\pom.xml:
Expected root element 'project' but found 'dependencies'
```

It also reported that these required POM elements were missing:

```text
modelVersion is missing
groupId is missing
artifactId is missing
version is missing
```

## Root cause

The POM started directly with:

```xml
<dependencies>
```

instead of having the required Maven `<project>` root and project metadata.

## Resolution

The entire `pom.xml` was replaced with a valid Spring Boot Maven project containing:

- `<project>` root element
- `modelVersion`
- Spring Boot parent
- `groupId`
- `artifactId`
- `version`
- Java 17 configuration
- Spring Web dependency
- Spring Data JPA dependency
- PostgreSQL JDBC driver
- Validation
- Spring Boot Test
- Spring Boot Maven plugin

After that, Maven could successfully parse the project and compile the source tree.

---

# 2. Maven Wrapper Problem

The next command was:

```powershell
mvnw.cmd spring-boot:run
```

PowerShell reported:

```text
mvnw.cmd : The term 'mvnw.cmd' is not recognized...
```

Checking the project showed that `mvnw.cmd` did not exist.

## Resolution

Maven itself was installed and working, so the Maven Wrapper was generated using:

```powershell
mvn wrapper:wrapper
```

This created the expected wrapper files:

```text
mvnw
mvnw.cmd
.mvn/
```

In PowerShell, the local wrapper is then executed with:

```powershell
.\mvnw.cmd spring-boot:run
```

rather than simply:

```powershell
mvnw.cmd spring-boot:run
```

---

# 3. Spring Boot Test Could Not Find the Application Class

After Maven could compile the project, the test failed with:

```text
Unable to find a @SpringBootConfiguration by searching packages upwards from the test.
```

The problem was in `MiniDriveApplication.java`.

It originally used:

```java
package main.java.com.minidrive;
```

## Root cause

The Java package declaration incorrectly included the source-directory path components `main.java`.

The class was physically located under:

```text
src/main/java/com/minidrive/MiniDriveApplication.java
```

Therefore the Java package needed to be:

```java
package com.minidrive;
```

## Resolution

The package was corrected to:

```java
package com.minidrive;
```

The class remained:

```java
@SpringBootApplication
public class MiniDriveApplication {
    public static void main(String[] args) {
        SpringApplication.run(MiniDriveApplication.class, args);
    }
}
```

The test and application now shared the expected package structure.

---

# 4. First PostgreSQL Docker Container Name Conflict

When starting the Docker Compose setup, Docker returned:

```text
Conflict. The container name "/minidrive-postgres" is already in use
```

An existing container named:

```text
minidrive-postgres
```

already existed from earlier work.

That old container used PostgreSQL 17, while the intended Compose configuration used PostgreSQL 16.

## Resolution

The old stopped container was removed, without blindly deleting Docker volumes:

```powershell
docker rm minidrive-postgres
```

Then the intended Compose PostgreSQL container was created.

The important principle was:

> Remove the conflicting container when necessary, but do not destroy the database volume unless there is a deliberate reason to reset the database.

---

# 5. PostgreSQL Container Was Healthy, but Authentication Failed

The new container was healthy:

```text
minidrive-postgres   postgres:16   Up ... (healthy)
```

`pg_isready` also confirmed that PostgreSQL was accepting connections inside the container:

```powershell
docker exec minidrive-postgres pg_isready -U minidrive -d minidrive
```

Result:

```text
/var/run/postgresql:5432 - accepting connections
```

However, a host-side PostgreSQL connection failed:

```powershell
psql -h localhost -p 5432 -U minidrive -d minidrive
```

with:

```text
FATAL: password authentication failed for user "minidrive"
```

## Important observation

The Docker environment was configured with:

```text
POSTGRES_DB=minidrive
POSTGRES_USER=minidrive
POSTGRES_PASSWORD=minidrive
```

and Spring Boot was configured with the same logical credentials.

However, the PostgreSQL data directory had already been initialized earlier.

For an already-initialized PostgreSQL volume, changing `POSTGRES_PASSWORD` in Docker Compose does **not** rewrite the existing database role password.

## Investigation

An attempt to connect using the conventional `postgres` administrator role failed:

```text
FATAL: role "postgres" does not exist
```

This established that the database cluster had been initialized with a different role setup and that `postgres` was not the administrator role in this existing cluster.

## Resolution

The existing `minidrive` role password was repaired in place, preserving the existing Docker volume and its data.

No volume reset was performed.

---

# 6. Windows PostgreSQL Service Was Also Using Port 5432

Even after repairing the database credentials, there was another host-level problem.

A Windows PostgreSQL 18 service named:

```text
postgresql-x64-18
```

was also listening on host port `5432`.

This meant a host-side connection to:

```text
localhost:5432
```

could reach the Windows PostgreSQL instance instead of the Docker PostgreSQL instance.

The Docker PostgreSQL container itself remained healthy.

## Evidence

The conflict was confirmed by inspecting the host/container setup and observing that another PostgreSQL service was already bound to port 5432.

Attempting to stop the Windows service was blocked by permissions.

## Resolution path considered

The preferred clean workaround was to move Docker PostgreSQL to a different host port while keeping PostgreSQL's internal container port unchanged:

```yaml
ports:
  - "5433:5432"
```

and then configure Spring Boot to use:

```text
jdbc:postgresql://localhost:5433/minidrive
```

This removes the host-port collision without changing PostgreSQL's internal listening port.

However, the final successful environment was able to verify host and in-container database access on host port 5432, so the port conflict was ultimately not the final blocker.

---

# 7. Hibernate Reported a Dialect Error

The Spring test repeatedly failed with:

```text
Unable to determine Dialect without JDBC metadata
```

The stack trace showed the failure while Hibernate was creating:

```text
entityManagerFactory
```

and initializing the JDBC environment.

## Initial temptation

Hibernate's message suggested adding a dialect such as:

```yaml
hibernate:
  dialect: org.hibernate.dialect.PostgreSQLDialect
```

We intentionally did **not** use that as the first fix.

## Reasoning

The project was supposed to connect to a real PostgreSQL database.

Hibernate normally obtains database metadata through JDBC. If JDBC setup fails, Hibernate may be unable to determine the dialect and report the dialect error as a downstream symptom.

Therefore the correct debugging strategy was:

```text
Hibernate dialect error
        ↓
Check JDBC connection
        ↓
Check PostgreSQL
        ↓
Check host routing
        ↓
Check credentials
        ↓
Check JVM/configuration
```

rather than hiding the underlying connection failure.

---

# 8. Final Root Cause — JVM Timezone

After the Docker, PostgreSQL, credentials, host routing, and Spring configuration were checked, the final root cause was identified:

```text
The JVM timezone was set to Asia/Calcutta.
```

PostgreSQL rejected that timezone during JDBC connection setup.

This meant the actual chain was:

```text
JVM timezone = Asia/Calcutta
            ↓
JDBC setup failed
            ↓
Hibernate could not obtain JDBC metadata
            ↓
Hibernate reported:
Unable to determine Dialect without JDBC metadata
            ↓
Spring ApplicationContext failed
```

## Why the Hibernate message was misleading

The Hibernate dialect exception was not the primary problem.

It was a consequence of the database connection failing before Hibernate could obtain the metadata it needed.

This is the most important lesson from the incident:

> When Hibernate says it cannot determine the dialect, verify database connectivity and JDBC initialization before forcing a dialect configuration.

---

# 9. Maven Local Repository Issue

There was also a separate Maven environment problem.

Maven initially attempted to resolve its local repository under an inaccessible location:

```text
C:\.m2\repository
```

This created an environment/cache issue separate from the application code.

The existing Maven cache was used for verification, allowing the project dependencies to resolve successfully.

## Result

Maven eventually completed both:

```text
mvn clean package
mvn clean test
```

successfully.

---

# 10. Actual Configuration Used

The Spring Boot datasource was configured against the local PostgreSQL database.

The working logical setup was:

```yaml
spring:
  application:
    name: minidrive

  datasource:
    url: jdbc:postgresql://localhost:5432/minidrive
    username: minidrive
    password: minidrive

  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
    open-in-view: false

server:
  port: 8080
```

The JVM was configured so the problematic timezone did not interfere with JDBC setup. UTC was used for the Maven test/run environment.

---

# 11. Files Changed During the Final Fix

The final Codex verification reported these checked-in file changes:

### `backend/pom.xml`

Added UTC JVM settings for Maven test execution and `spring-boot:run`.

### `backend/src/main/resources/application.yml`

Configured the local PostgreSQL datasource and JPA settings.

### `.gitignore`

Added:

```text
**/target/
```

so Maven build output is ignored.

### `.env.example`

Changed the password value to a placeholder rather than treating a real password as a committed secret.

The application class, health controller, Maven Wrapper files, profile deletions, and other pre-existing/untracked changes were left as found by Codex and were not unnecessarily rewritten.

---

# 12. Final Verification

The complete Phase 1 verification succeeded.

## PostgreSQL

Version:

```text
PostgreSQL 16.15
```

The Docker database remained on the existing:

```text
infra_postgres_data
```

volume.

No volume reset was performed.

## Docker

Container:

```text
minidrive-postgres
```

Status:

```text
healthy
```

## Database

Both host and in-container `SELECT 1` checks succeeded.

## Maven

Both commands succeeded:

```powershell
mvn clean package
mvn clean test
```

The Maven Wrapper also launched successfully.

## Spring Boot

The application started successfully with:

```powershell
mvn spring-boot:run
```

Hikari successfully connected to PostgreSQL.

Hibernate initialized correctly.

## Test

The test:

```text
MiniDriveApplicationTests.contextLoads
```

passed against the real PostgreSQL database.

## Health Endpoint

Request:

```text
GET http://localhost:8080/api/health
```

Response:

```json
{
  "status": "UP",
  "service": "MiniDrive"
}
```

---

# 13. Final Phase 1 Status

```text
Phase 1 — Spring Boot + PostgreSQL

Maven                         ✅
Valid POM                    ✅
Package structure            ✅
Spring Boot configuration    ✅
Docker PostgreSQL             ✅
PostgreSQL authentication    ✅
JDBC connectivity            ✅
JPA/Hibernate                 ✅
ApplicationContext            ✅
Integration-style test        ✅
Application startup           ✅
Health endpoint               ✅
Database volume preserved     ✅
```

**Phase 1 is complete and verified.**

---

# 14. Debugging Lessons

## Lesson 1 — Read the deepest `Caused by`

`Failed to load ApplicationContext` is usually a wrapper. The useful information is generally deeper in the stack trace.

## Lesson 2 — Do not fix downstream symptoms first

The Hibernate dialect error looked like a Hibernate configuration problem, but the actual root cause was the failed JDBC setup caused by the JVM timezone.

## Lesson 3 — `pg_isready` is not the same as authentication success

A healthy PostgreSQL container only proves that PostgreSQL is running and accepting server connections.

It does not prove that:

- the username is correct
- the password is correct
- the host connection reaches the intended PostgreSQL instance
- JDBC can establish a session

## Lesson 4 — Existing PostgreSQL volumes retain their initialization state

Docker Compose environment variables such as `POSTGRES_PASSWORD` initialize a new database cluster. They do not automatically rewrite an already-initialized role password.

## Lesson 5 — Host ports can hide service conflicts

A container can be perfectly healthy internally while host traffic is reaching a completely different service.

## Lesson 6 — Preserve data before deleting infrastructure

The PostgreSQL volume was preserved because there was no demonstrated need to destroy it.

## Lesson 7 — Verify the whole chain

A build passing is not enough for a backend foundation.

The real acceptance chain is:

```text
Docker
  ↓
PostgreSQL
  ↓
JDBC
  ↓
Hikari
  ↓
Hibernate/JPA
  ↓
Spring Context
  ↓
Application
  ↓
HTTP endpoint
```

---

# 15. Current Project Position

MiniDrive is now ready to move to **Phase 2: Authentication**.

Phase 2 should build on this verified foundation rather than reopening the Phase 1 infrastructure unless a new concrete failure appears.

The later architecture remains intentionally deferred:

```text
Phase 1  → Spring Boot + PostgreSQL                 ✅
Phase 2  → Authentication
Phase 3  → Users, folders, file metadata
Phase 4  → MinIO
Phase 5  → Upload/download/delete
Phase 6  → Permissions and sharing
Phase 7  → Versioning
Phase 8  → Chunked/resumable uploads
Phase 9  → Concurrency/idempotency/checksums
Phase 10 → Redis
Phase 11 → Multiple storage nodes
Phase 12 → Replication/recovery
Phase 13 → Monitoring/load testing
```

---

# 16. One-Line Incident Summary

**MiniDrive Phase 1 initially failed through a sequence of POM, Java package, Docker container, PostgreSQL authentication, host-port, and environment issues; the decisive runtime root cause was the JVM using `Asia/Calcutta`, which PostgreSQL rejected during JDBC setup, causing Hibernate's misleading dialect error. After configuring UTC and cleaning up the Maven/runtime configuration, the real PostgreSQL-backed Spring context passed and the application health endpoint worked.**
