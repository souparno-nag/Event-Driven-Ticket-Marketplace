# T033 — Eureka, deferred to build step 7

**What this task did:** decided *not* to add Eureka to `infra/docker-compose.yml`, and recorded why.
No service was added. Phase 4 ships six containers rather than seven.

This is a decision record. It exists because "we skipped it" is worthless six months from now
without the reasoning attached.

---

## What Eureka is

A **service registry** — a phone book that services write to themselves.

Services do not have fixed addresses. In Kubernetes a pod gets a new IP on every restart; scaled to
three replicas there are three addresses changing independently. Hardcoding
`http://order-service:8081` breaks the moment anything moves.

Eureka solves that in four moves:

1. **Register** — on startup, order-service tells Eureka *"I am `ORDER-SERVICE`, reachable at
   192.168.1.14:8081"*.
2. **Heartbeat** — it renews that lease every 30s. Miss enough and Eureka evicts the entry, so
   callers stop being sent to a dead instance.
3. **Look up** — a caller asks for `ORDER-SERVICE` and receives the current list of instances.
4. **Client-side load balancing** — with three instances registered, the *caller* chooses one,
   rather than routing through a central proxy.

## What it does in *this* system — less than you would expect

Worth being precise: **Eureka plays no part in the saga.**

The saga is choreographed over Kafka. Order-service never calls inventory-service — it publishes
`OrderCreated` and inventory-service consumes it. Nobody looks anybody up. The happy path and both
compensation paths run with no registry in existence.

Its single real consumer arrives at build step 7, in the gateway:

```yaml
spring.cloud.gateway.routes:
  - id: orders
    uri: lb://order-service        # "lb://" = resolve through the registry, then pick an instance
    predicates:
      - Path=/api/orders/**
```

Without Eureka that becomes a hardcoded `http://localhost:8081`, which works on one laptop and
nowhere else.

So: **one consumer, one purpose, zero involvement in the saga.**

---

## Why there is no image to pull

The task as written asked for a `docker pull`-able service like the other six. No such image exists,
and the reason is a genuinely useful distinction:

| | What it is | How you get it |
|---|---|---|
| Postgres, Redis, Kafka | **Servers** — finished programs you configure and run | official image |
| Eureka | **A library** you embed in your own app | a Maven dependency |

Postgres ships an image because Postgres is a product. Netflix released Eureka as a *server
library*, and Spring Cloud wraps it so that a Eureka server is an app **you write**:

```java
@SpringBootApplication
@EnableEurekaServer          // ← this annotation is the entire server
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

Two starters exist and the difference matters:

- `spring-cloud-starter-netflix-eureka-**server**` — makes an app *be* the registry. Exactly one
  module.
- `spring-cloud-starter-netflix-eureka-**client**` — makes an app *register with* the registry. All
  six services.

What is on Docker Hub is only what someone once wrapped for themselves: `springcloud/eureka` has a
single tag from around 2016 running Spring Boot 1.x — its health endpoint is `/health`, not the
`/actuator/health` R8 specifies. `netflixoss/eureka` is the raw WAR with no actuator at all. And
`steeltoedeveloper/eureka-server`, which the task appears to assume, **does not exist**.

---

## The actual reason for deferring

Not the missing image — that is a nuisance, not an argument. The structural point is this:

> Every other service in `docker-compose.yml` is **third-party infrastructure**. Eureka would be
> **our own code**.

Today, `docker compose --profile full up` starts the entire environment without compiling one line
of this project. That is not an accident; it is what makes Phase 4 verifiable on its own, and what
lets someone clone the repository and have working infrastructure before understanding any Java.

Adding Eureka means a `build:` context, so bringing up the environment would first require building
a Maven module. Phase 4 would no longer be *infrastructure* — it would be infrastructure plus a
build step, and T042's verification could no longer run against a clean checkout.

The plan's own research reached the same grouping. R9 lists *"the JVM services built in this project
(**Eureka now**; order, inventory, payment, and projection later)"* — placing Eureka with our
services, not with Kafka and Postgres. T033's wording contradicts the research it was derived from,
and the research is the more considered document.

A supporting point: nothing registers with Eureka until step 7. Running it now yields an empty
registry that proves nothing works, only that a process started.

## What this is not

**This is not cutting Eureka from the project.** It still gets built, all six services still
register with it, and it still backs `lb://` routing in the gateway. Every technology in the brief's
stack remains in the finished system.

The change is *when*, and the answer is: at the point where it is used, alongside the gateway that
is its only consumer.

---

## What step 7 will need to do

Recorded here so it is not rediscovered:

1. **Add the Spring Cloud BOM to the root `pom.xml`.** `spring-boot-starter-parent` pins Boot's
   dependencies but knows nothing about Spring Cloud versions, so
   `spring-cloud-starter-netflix-eureka-server` would resolve to no version at all:

   ```xml
   <dependencyManagement>
     <dependencies>
       <dependency>
         <groupId>org.springframework.cloud</groupId>
         <artifactId>spring-cloud-dependencies</artifactId>
         <version>2023.0.6</version>   <!-- the release train matching Boot 3.3.x -->
         <type>pom</type>
         <scope>import</scope>
       </dependency>
     </dependencies>
   </dependencyManagement>
   ```

   One-time, and the gateway and Resilience4j need the same BOM.

2. **Scaffold a `eureka-server` module** from start.spring.io — Spring Boot 3.3.x, the Eureka Server
   dependency.

3. **Configure it not to register with itself.** A lone server otherwise hunts for peers and logs
   warnings forever:

   ```yaml
   server.port: 8761
   eureka.client.register-with-eureka: false
   eureka.client.fetch-registry: false
   ```

4. **Give it a Dockerfile**, then a Compose service with `build: ../eureka-server`, `mem_limit: 384m`,
   `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65`, profile `full`, and an actuator health probe — the
   settings T033 specified, now attached to something buildable.

### Why it needs its own module

The tempting shortcut is to put `@EnableEurekaServer` on an existing service and skip the extra
module. Don't: if order-service goes down, the registry goes down with it, and now nothing can find
anything. **A registry has to outlive the things it tracks.** That is why it is a separate
deployable despite being the smallest thing in the project.

---

## What changed in the repository

- `tasks.md` — T033 rewritten as `DEFERRED to build step 7`, left **unchecked** because it is not
  done. A checked box would claim work that has not happened.
- `tasks.md` — T042 now notes that `full` is six services, so the later verification does not go
  looking for a seventh.
- `docker-compose.yml` — the header's footprint corrected from `~3 GiB` to `~2.6 GiB`, with the
  absence explained where anyone reading the file will meet it.

That last one matters more than it looks. A stale comment claiming seven services would send someone
hunting for a bug that is actually a decision.

---

## What comes next

**T037** — `infra/.env`, the one file that selects which profile is active.

Then **T038** (`depends_on` health conditions), **T039–T040** (the Makefile, where `make up` finally
exists), and **T041** (`infra/README.md`).
