# CareerMesh Microservices System

A production-grade, cloud-native job portal backend built with a microservices architecture using **Spring Boot 3**, **Spring Cloud**, **Docker**, and **Kubernetes**. This system demonstrates real-world enterprise patterns including distributed rate limiting, asynchronous event-driven communication, distributed caching, service discovery, distributed tracing, circuit breaking with retry logic, and Horizontal Pod Autoscaling.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Microservices Breakdown](#microservices-breakdown)
   - [Company Microservice](#1-company-microservice-port-8081)
   - [Job Microservice](#2-job-microservice-port-8082)
   - [Review Microservice](#3-review-microservice-port-8083)
   - [API Gateway](#4-api-gateway-port-8084)
3. [Technology Stack](#technology-stack)
4. [Key Enterprise Patterns Implemented](#key-enterprise-patterns-implemented)
5. [Data Models & Domain Design](#data-models--domain-design)
6. [Inter-Service Communication](#inter-service-communication)
7. [Asynchronous Messaging (RabbitMQ)](#asynchronous-messaging-rabbitmq)
8. [API Reference](#api-reference)
9. [Running the System](#running-the-system)
   - [Method 1: Local JARs (Development)](#method-1-running-locally-with-jar-files-development-mode)
   - [Method 2: Docker Compose (Integration Testing)](#method-2-running-with-docker-compose-recommended-for-demos)
   - [Method 3: Kubernetes on Minikube (Production-Like)](#method-3-deploying-on-kubernetes-minikube)
10. [Rate Limiting Verification](#rate-limiting-verification)
11. [Distributed Tracing with Zipkin](#distributed-tracing-with-zipkin)
12. [Project Structure](#project-structure)

---

## Architecture Overview

```
                                  ┌─────────────────────────────────────┐
                                  │             Clients                  │
                                  │  (Browser / Postman / Load Tester)   │
                                  └───────────────┬─────────────────────┘
                                                  │
                                                  ▼
                              ┌───────────────────────────────────────┐
                              │           API Gateway :8084            │
                              │   Spring Cloud Gateway + Rate Limiter  │
                              │      (Redis Token Bucket Algorithm)    │
                              └───────┬───────────────────────────────┘
                                      │  Routes by Path:
                       ┌──────────────┼──────────────────────┐
                       ▼              ▼                       ▼
              /companies/**      /jobs/**               /reviews/**
                       │              │                       │
         ┌─────────────▼──┐  ┌────────▼──────┐  ┌───────────▼────┐
         │  Company MS     │  │   Job MS      │  │  Review MS     │
         │  :8081          │  │   :8082       │  │  :8083         │
         │  PostgreSQL DB  │  │  PostgreSQL   │  │  PostgreSQL    │
         │  (company db)   │  │  (job db)     │  │  (review db)   │
         └────────┬────────┘  └───────┬───────┘  └──────┬─────────┘
                  │                   │                   │
                  │    Feign Client    │                   │
                  │  ◄────────────────┘                   │
                  │    Feign Client                        │
                  │  ◄────────────────────────────────────┘
                  │
                  └──────────────────┐
                                     ▼
                        ┌────────────────────────┐
                        │       RabbitMQ          │
                        │  companyRatingQueue     │
                        │  companyDeletedQueue    │
                        │  jobDeletionFailedQueue │
                        └────────────────────────┘

         ┌─────────────────────────────────────────────────────────┐
         │                  Shared Infrastructure                   │
         │  Redis (Caching + Rate Limiting) | Zipkin (Tracing)      │
         │  Eureka (Service Discovery - local/docker mode only)     │
         └─────────────────────────────────────────────────────────┘
```

### How Requests Flow

1. A client sends a request to the **API Gateway** on port `8084`.
2. The Gateway checks the **Redis rate limiter** using a Token Bucket algorithm (10 req/s replenish rate, burst of 20). If the limit is exceeded, it immediately returns `HTTP 429 Too Many Requests`.
3. If the request is allowed, the Gateway routes it to the appropriate microservice based on the URL path:
   - `/companies/**` → Company MS
   - `/jobs/**` → Job MS
   - `/reviews/**` → Review MS
4. In **local/Docker mode**, the Gateway uses **Eureka service discovery** for load-balanced routing (`lb://SERVICE-NAME`).
5. In **Kubernetes mode**, Eureka is disabled. The Gateway routes directly to Kubernetes Services by DNS name (`http://company:80`, `http://job:80`, `http://review:80`).

---

## Microservices Breakdown

### 1. Company Microservice (Port 8081)

**Responsibility:** Manages company profiles including their name, description, and aggregated review rating.

**Spring Boot App:** `CompanymsApplication.java`  
**Eureka Service Name:** `COMPANY-SERVICE`  
**Database:** PostgreSQL (`company` database)

**Key Features:**
- Full CRUD REST API for companies.
- **Redis caching** (via Redisson) on `getCompanyById` with `@Cacheable`. Cache is automatically evicted on `update` and `delete` via `@CacheEvict`.
- **OpenFeign client** (`ReviewClient`) calls the Review MS to fetch average ratings.
- **RabbitMQ Producer:** Publishes a `companyDeletedEvent` message to `companyDeletedQueue` when a company is deleted, so the Review MS can cascade-delete all associated reviews.
- **RabbitMQ Consumer:** Listens to `companyRatingQueue`. When a new review is created (event published by Review MS), it receives the message, calls the Review MS for the updated average rating via Feign, and updates the company's `rating` field automatically.
- Batch fetch endpoint (`POST /companies/batch`) to support efficient N+1 query prevention.

**Dependencies:** `spring-boot-starter-data-jpa`, `spring-boot-starter-web`, `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-openfeign`, `spring-boot-starter-cache`, `redisson-spring-boot-starter`, `spring-boot-starter-amqp`, `spring-boot-starter-actuator`, `micrometer-tracing-bridge-brave`, `zipkin-reporter-brave`, `postgresql`

---

### 2. Job Microservice (Port 8082)

**Responsibility:** Manages job postings and provides an aggregated view by combining data from Company and Review microservices.

**Spring Boot App:** `JobmsApplication.java`  
**Eureka Service Name:** `JOB-SERVICE`  
**Database:** PostgreSQL (`job` database)

**Key Features:**
- Full CRUD REST API for jobs.
- **Aggregated `JobDTO` response:** Every `GET /jobs` or `GET /jobs/{id}` call returns a rich `JobDTO` that embeds the full `Company` object and a list of `Review` objects — assembled from 3 different services in real time using Feign calls.
- **JPA Specification-based filtering:** `GET /jobs/filter?minSalary=X&location=Y&title=Z` uses `JobSpecification` with the JPA Criteria API for dynamic, multi-field filtering.
- **Full-text search:** `GET /jobs/search?query=X` searches across both `title` and `location` fields.
- **Database indexes** on `title` and `location` columns for query performance.
- **Batch fetching optimization** in `filterJobs`: Collects all unique `companyId`s first, then makes a single batch call to Company MS and Review MS, preventing N+1 query problems.
- **Resilience4j Retry:** All inter-service calls use `@Retry(name = "companyBreaker")` with a fallback method that returns a user-friendly error message when the Company MS is down.
- **Redis caching** (Redisson) on `getJobById` with cache eviction on update and delete.
- **RabbitMQ Consumer:** Listens to `companyDeletedQueue` and cascades deletes all jobs belonging to the deleted company.
- **Spring Cloud Config Client:** Can be connected to a centralized Config Server.

**Dependencies (in addition to company MS deps):** `spring-cloud-starter-config`, `spring-cloud-starter-circuitbreaker-resilience4j`, `spring-boot-starter-aop`, `spring-boot-starter-amqp`

---

### 3. Review Microservice (Port 8083)

**Responsibility:** Manages user reviews for companies.

**Spring Boot App:** `ReviewmsApplication.java`  
**Eureka Service Name:** `REVIEW-SERVICE`  
**Database:** PostgreSQL (`review` database)

**Key Features:**
- Full CRUD REST API for reviews (scoped by `companyId` as a query parameter).
- **Average Rating calculation:** `GET /reviews/averageRating?companyId=X` returns the computed average rating for a company — this is called by the Company MS via Feign.
- **RabbitMQ Producer:** After a review is created, publishes a `ReviewMessage` to `companyRatingQueue` so that the Company MS can update its cached rating.
- **RabbitMQ Consumer:** Listens to `companyDeletedQueue`. When a company is deleted, it automatically cascade-deletes all reviews belonging to that company using an event-driven pattern (Saga pattern).
- Batch review fetch endpoint (`POST /reviews/batch`) for efficient bulk lookups by multiple company IDs.

---

### 4. API Gateway (Port 8084)

**Responsibility:** Single entry point for all client traffic. Provides routing, distributed rate limiting, and observability.

**Spring Boot App:** `GatewayApplication.java`  
**Framework:** Spring Cloud Gateway (Reactive, Netty-based)

**Key Features:**
- **Path-based routing** to all 3 microservices.
- **Redis-backed Distributed Rate Limiting** using Spring Cloud Gateway's `RequestRateLimiter` filter with the **Token Bucket algorithm**:
  - `replenishRate: 10` — 10 tokens added per second.
  - `burstCapacity: 20` — Maximum of 20 requests can be served in a burst.
  - Key is resolved **per IP address** (`ipKeyResolver` bean).
  - Returns `HTTP 429 Too Many Requests` when the bucket is empty.
- **Eureka Route discovery** in local/Docker mode (uses `lb://SERVICE-NAME` URIs).
- **Direct K8s Service routing** in Kubernetes mode (Eureka disabled, routes to `http://service-name:80`).
- **Distributed tracing** via Zipkin for all routed requests.
- Exposes the Eureka dashboard via a dedicated route (`/eureka/main`).

**Three Configuration Profiles:**
| Profile | Redis Host | Routing | Eureka |
|---------|-----------|---------|--------|
| `default` (local JARs) | `localhost` | `lb://SERVICE-NAME` via Eureka | Enabled |
| `docker` | `redis` | Direct container name | Enabled |
| `k8s` | `redis` (K8s Service) | `http://company:80` etc. | **Disabled** |

---

## Technology Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Language | Java | 21 (Microservices), 17 (Gateway) |
| Framework | Spring Boot | 3.5.9 |
| Service Discovery | Spring Cloud Netflix Eureka | 2025.0.1 |
| API Gateway | Spring Cloud Gateway | 2025.0.0 |
| Inter-Service Comms | Spring Cloud OpenFeign | 2025.0.1 |
| Fault Tolerance | Resilience4j (Retry, Circuit Breaker) | included in Spring Cloud |
| Message Broker | RabbitMQ | 3-management |
| Database | PostgreSQL | 15 |
| Distributed Cache | Redis + Redisson | 3.27.0 |
| Rate Limiting | Spring Cloud Gateway RequestRateLimiter | — |
| Distributed Tracing | Micrometer Tracing + Zipkin (Brave) | — |
| Containerization | Docker | — |
| Container Orchestration | Kubernetes (Minikube for local) | — |
| Autoscaling | Kubernetes HPA | autoscaling/v2 |
| Build Tool | Apache Maven | 3.x |

---

## Key Enterprise Patterns Implemented

### 1. Distributed Rate Limiting (Token Bucket)
The API Gateway uses Redis to implement a **distributed token bucket** rate limiter. Because Redis holds the token count, this works correctly even if you scale the Gateway to multiple replicas — all replicas share the same counter.

- Burst of 20 requests
- Replenishes 10 tokens/second
- Per-IP key resolution
- Returns `X-RateLimit-Remaining`, `X-RateLimit-Burst-Capacity`, `X-RateLimit-Replenish-Rate` response headers

### 2. Event-Driven Architecture (Saga Pattern)
Three RabbitMQ queues coordinate cross-service data consistency without direct service coupling:

| Queue | Producer | Consumer | Purpose |
|-------|----------|----------|---------|
| `companyRatingQueue` | Review MS | Company MS | When a review is added, Company MS updates its cached rating |
| `companyDeletedQueue` | Company MS | Review MS + Job MS | When a company is deleted, both Review and Job cascade-delete related records |
| `jobDeletionFailedQueue` | Job MS | — | Compensation queue for failed job deletions (compensating transaction) |

### 3. Distributed Caching (Redisson/Redis)
Both the Company MS and Job MS cache individual entity lookups in Redis using Spring's `@Cacheable` / `@CacheEvict` annotations backed by Redisson.

- `GET /companies/{id}` → cached under key `company::{id}`
- `GET /jobs/{id}` → cached under key `job::{id}`
- Cache is automatically evicted when an entity is updated or deleted

### 4. Resilience4j Retry + Circuit Breaker
The Job MS wraps all Feign calls with `@Retry(name = "companyBreaker")`. If the Company MS or Review MS is temporarily unavailable, Resilience4j retries the call automatically. If all retries fail, it invokes the `companyBreakerFallback` method which returns a user-friendly error message instead of crashing.

### 5. Batch Fetching (N+1 Prevention)
The `filterJobs` endpoint demonstrates production-quality query optimization: instead of making one HTTP call per job (N+1 problem), it:
1. Collects all distinct `companyId`s from the job query result.
2. Makes a **single batch HTTP call** to Company MS (`POST /companies/batch`).
3. Makes a **single batch HTTP call** to Review MS (`POST /reviews/batch`).
4. Maps the results in-memory using Java Streams.

### 6. JPA Specifications (Dynamic Filtering)
`JobSpecification` uses the JPA Criteria API to dynamically build `Predicate` chains. Only non-null, non-empty parameters are added to the `WHERE` clause, making the filter completely flexible.

### 7. Kubernetes HPA (Horizontal Pod Autoscaling)
All three microservices have `HorizontalPodAutoscaler` manifests that scale pods between 1 and 4 replicas based on CPU utilization (threshold: 70%).

### 8. Kubernetes Readiness Probes
All deployments have `readinessProbe` configured pointing to `/actuator/health`. Kubernetes will **never** send traffic to a pod until Spring Boot has finished initializing (connecting to DB, RabbitMQ, Redis), preventing any premature 500 errors.

---

## Data Models & Domain Design

### Company
```java
@Entity
public class Company {
    Long id;           // Auto-generated primary key
    String name;       // Company name
    String description; // Company description
    double rating;     // Aggregated average from all reviews (auto-updated via RabbitMQ)
}
```

### Job
```java
@Entity
@Table(indexes = { @Index(columnList = "title"), @Index(columnList = "location") })
public class Job {
    Long id;           // Auto-generated primary key
    String title;      // Job title (indexed)
    String description; // Job description
    String minSalary;  // Minimum salary
    String maxSalary;  // Maximum salary
    String location;   // Job location (indexed)
    Long companyId;    // Foreign key reference to Company (cross-service)
}
```

### Review
```java
@Entity
public class Review {
    Long id;           // Auto-generated primary key
    String title;      // Review title
    String description; // Review body
    double rating;     // Rating (used to calculate company average)
    Long companyId;    // Which company this review belongs to
}
```

### JobDTO (Aggregated Response Object)
When a client requests a job, the response includes the full company and its reviews embedded:
```json
{
  "id": 1,
  "title": "Senior Software Engineer",
  "description": "...",
  "minSalary": "80000",
  "maxSalary": "120000",
  "location": "Bangalore",
  "company": {
    "id": 1,
    "name": "TechCorp",
    "description": "...",
    "rating": 4.5
  },
  "reviews": [
    { "id": 1, "title": "Great place", "description": "...", "rating": 4.5, "companyId": 1 }
  ]
}
```

---

## Inter-Service Communication

### Synchronous (OpenFeign)

**Job MS → Company MS:**
```java
@FeignClient(name="COMPANY-SERVICE", url="${company-service.url}")
public interface CompanyClient {
    @GetMapping("/companies/{id}")
    Company getCompany(@PathVariable Long id);

    @PostMapping("/companies/batch")
    List<Company> getCompaniesByIds(@RequestBody List<Long> ids);
}
```

**Job MS → Review MS:**
```java
@FeignClient(name="REVIEW-SERVICE", url="${review-service.url}")
public interface ReviewClient {
    @GetMapping("/reviews")
    List<Review> getReviews(@RequestParam Long companyId);

    @PostMapping("/reviews/batch")
    List<Review> getReviewsByCompanyIds(@RequestBody List<Long> companyIds);
}
```

**Company MS → Review MS:**
```java
@FeignClient(name="REVIEW-SERVICE", url="${review-service.url}")
public interface ReviewClient {
    @GetMapping("/reviews/averageRating")
    Double getAverageRating(@RequestParam Long companyId);
}
```

### Asynchronous (RabbitMQ)

All messages are serialized to **JSON** using `Jackson2JsonMessageConverter`.

**Review Created Event (Review MS → Company MS):**
```json
{ "id": 5, "title": "Good Place", "description": "...", "rating": 4.0, "companyId": 1 }
```

**Company Deleted Event (Company MS → Review MS + Job MS):**
```json
{ "id": 1, "name": "TechCorp" }
```

---

## Asynchronous Messaging (RabbitMQ)

The system implements a **Saga-like choreography pattern** using RabbitMQ. No service directly calls another for destructive operations — they publish events and each service reacts independently.

### Review Creation Flow
```
Client → POST /reviews?companyId=1
         └── Review MS saves review to DB
             └── Publishes ReviewMessage to "companyRatingQueue"
                 └── Company MS consumes message
                     └── Calls Review MS Feign for average rating
                         └── Updates company.rating in DB
                             └── Evicts company cache from Redis
```

### Company Deletion Flow
```
Client → DELETE /companies/1
         └── Company MS publishes CompanyMessage to "companyDeletedQueue"
             └── Company MS deletes company from DB
                 ├── Review MS consumes "companyDeletedQueue"
                 │   └── Deletes all reviews where companyId = 1
                 └── Job MS consumes "companyDeletedQueue"
                     └── Deletes all jobs where companyId = 1
```

---

## API Reference

All endpoints below are accessible through the **API Gateway on port 8084** (or directly to each service on its native port).

### Company Endpoints (`/companies`)

| Method | Endpoint | Description | Request Body | Response |
|--------|----------|-------------|-------------|----------|
| `GET` | `/companies` | Get all companies | — | `200 List<Company>` |
| `GET` | `/companies/{id}` | Get company by ID (cached) | — | `200 Company` / `404` |
| `POST` | `/companies` | Create a new company | `{ "name": "...", "description": "..." }` | `201 "Company added successfully"` |
| `PUT` | `/companies/{id}` | Update a company (cache evicted) | `{ "name": "...", "description": "..." }` | `200 "Company updated successfully"` / `404` |
| `DELETE` | `/companies/{id}` | Delete company (triggers cascade via RabbitMQ) | — | `200 "Company deleted successfully"` / `404` |
| `POST` | `/companies/batch` | Get companies by list of IDs (for internal batch fetching) | `[1, 2, 3]` | `200 List<Company>` |

### Job Endpoints (`/jobs`)

| Method | Endpoint | Description | Request Body / Params | Response |
|--------|----------|-------------|----------------------|----------|
| `GET` | `/jobs` | Get all jobs with full company + reviews | — | `200 List<JobDTO>` |
| `GET` | `/jobs/{id}` | Get job by ID (cached) with company + reviews | — | `200 JobDTO` / `404` |
| `GET` | `/jobs/search?query=X` | Full-text search on title and location | `query` param | `200 List<JobDTO>` |
| `GET` | `/jobs/filter?title=X&location=Y&minSalary=Z` | Dynamic multi-field filtering | Optional query params | `200 List<JobDTO>` |
| `POST` | `/jobs` | Create a new job | `{ "title": "...", "description": "...", "minSalary": "50000", "maxSalary": "80000", "location": "...", "companyId": 1 }` | `201 "Job added successfully"` |
| `PUT` | `/jobs/{id}` | Update a job (cache evicted) | Same as POST body | `200 "Job updated successfully"` / `404` |
| `DELETE` | `/jobs/{id}` | Delete a job (cache evicted) | — | `200 "Job deleted successfully"` / `404` |

### Review Endpoints (`/reviews`)

| Method | Endpoint | Description | Request Body / Params | Response |
|--------|----------|-------------|----------------------|----------|
| `GET` | `/reviews?companyId=X` | Get all reviews for a company | `companyId` param | `200 List<Review>` |
| `GET` | `/reviews/{reviewId}` | Get a specific review | — | `200 Review` |
| `GET` | `/reviews/averageRating?companyId=X` | Calculate average rating for a company | `companyId` param | `200 double` |
| `POST` | `/reviews?companyId=X` | Add a review (triggers RabbitMQ rating update) | `{ "title": "...", "description": "...", "rating": 4.5 }` | `201 "Review Added successfully"` / `404` |
| `PUT` | `/reviews/{reviewId}` | Update a review | Same as POST body | `200 "Review updated successfully"` / `404` |
| `DELETE` | `/reviews/{reviewId}` | Delete a review | — | `200 "Review deleted successfully"` / `404` |
| `POST` | `/reviews/batch` | Get reviews by list of company IDs (for internal batch fetching) | `[1, 2, 3]` | `200 List<Review>` |

---

## Running the System

### Prerequisites

Ensure the following tools are installed before proceeding with **any** of the three methods:

| Tool | Minimum Version | Download |
|------|----------------|----------|
| Java JDK | 21 | [adoptium.net](https://adoptium.net/) |
| Apache Maven | 3.8+ | [maven.apache.org](https://maven.apache.org/download.cgi) |
| Git | Any | [git-scm.com](https://git-scm.com/) |
| Docker Desktop | 24.0+ | [docker.com](https://www.docker.com/products/docker-desktop/) |

**Additional for Kubernetes:**

| Tool | Minimum Version | Download |
|------|----------------|----------|
| Minikube | 1.32+ | [minikube.sigs.k8s.io](https://minikube.sigs.k8s.io/docs/start/) |
| kubectl | 1.28+ | [kubernetes.io](https://kubernetes.io/docs/tasks/tools/) |

---

### Clone the Repository

```bash
git clone https://github.com/Jos-Samuel/CareerMesh-Microservices-System.git
cd CareerMesh-Microservices-System
```

---

### Method 1: Running Locally with JAR Files (Development Mode)

This method runs everything directly on your machine. Use this for active development.

#### Step 1: Start Infrastructure (Docker)

Start PostgreSQL, RabbitMQ, Redis, and Zipkin using Docker:

```bash
cd companyms

# Ensure no conflicting containers are running from previous tests
docker compose down

# Start only the required infrastructure
docker compose -f docker-compose.yaml up -d postgres rabbitmq redis zipkin
```

Wait for all 4 containers to be `healthy` (about 10-15 seconds):
```bash
docker ps
```

#### Step 2: Create PostgreSQL Databases

Connect to Postgres and create the three databases:

```bash
docker exec -it postgres psql -U jos -d postgres -c "CREATE DATABASE company;"
docker exec -it postgres psql -U jos -d postgres -c "CREATE DATABASE job;"
docker exec -it postgres psql -U jos -d postgres -c "CREATE DATABASE review;"
```

> **Note:** The PostgreSQL username is `jos` and password is `MJbyju@33201` (as configured in `companyms/docker-compose.yaml`).

#### Step 3: Start the Service Registry (Eureka)

The microservices register themselves with Eureka for service discovery. Open a new terminal for each service.

```bash
# Navigate back to the project root if you are still in companyms
cd ..
cd service-reg
./mvnw spring-boot:run
```

Wait until you see `Started ...Application` in the logs. Eureka UI is at: `http://localhost:8761`

#### Step 4: Start the Microservices

Open a **new terminal for each** of the following. Each service uses the `default` Spring profile which connects to `localhost` for all dependencies.

**Company MS:**
```bash
# From the project root:
cd companyms
./mvnw spring-boot:run
```

**Job MS:**
```bash
# From the project root:
cd jobms
./mvnw spring-boot:run
```

**Review MS:**
```bash
# From the project root:
cd reviewms
./mvnw spring-boot:run
```

#### Step 5: Start the API Gateway

```bash
# From the project root:
cd gateway
./mvnw spring-boot:run
```

#### Step 6: Verify

All services should register with Eureka. Open `http://localhost:8761` to confirm you see:
- `COMPANY-SERVICE`
- `JOB-SERVICE`
- `REVIEW-SERVICE`
- `gateway`

Test via the Gateway:
```bash
# Get all companies
curl http://localhost:8084/companies

# Create a company
curl -X POST http://localhost:8084/companies \
  -H "Content-Type: application/json" \
  -d '{"name":"TechCorp","description":"A great tech company"}'

# Create a job
curl -X POST http://localhost:8084/jobs \
  -H "Content-Type: application/json" \
  -d '{"title":"Backend Engineer","description":"Spring Boot dev","minSalary":"80000","maxSalary":"120000","location":"Bangalore","companyId":1}'

# Get all jobs (with company + reviews embedded)
curl http://localhost:8084/jobs
```

---

### Method 2: Running with Docker Compose (Recommended for Demos)

This method builds all images using the project's `Dockerfile`s and runs the entire system in Docker. No local Java installation needed for running (only for building).

#### Step 1: Build All JAR Files

From the project root, build each microservice:

```bash
cd companyms && ./mvnw clean package -DskipTests && cd ..
cd jobms && ./mvnw clean package -DskipTests && cd ..
cd reviewms && ./mvnw clean package -DskipTests && cd ..
cd gateway && ./mvnw clean package -DskipTests && cd ..
```

#### Step 2: Build Docker Images

```bash
cd companyms
docker build -t josamuel7/companyms:latest .
cd ..

cd jobms
docker build -t josamuel7/jobms:latest .
cd ..

cd reviewms
docker build -t josamuel7/reviewms:latest .
cd ..

cd gateway
docker build -t josamuel7/gateway-ms:latest .
cd ..
```

#### Step 3: Start Everything with Docker Compose

```bash
cd companyms
docker compose up -d
```

This starts: `postgres`, `rabbitmq`, `zipkin`, `redis`, `servicereg` (Eureka), `companyms`, `jobms`, `reviewms`, and `gateway-ms`.

#### Step 4: Create Databases

The first time you start, create the databases inside Postgres:
```bash
docker exec -it postgres psql -U jos -d postgres -c "CREATE DATABASE company;"
docker exec -it postgres psql -U jos -d postgres -c "CREATE DATABASE job;"
docker exec -it postgres psql -U jos -d postgres -c "CREATE DATABASE review;"
```

Restart the microservices after creating the databases:
```bash
docker compose restart companyms jobms reviewms
```

#### Step 5: Verify

Wait about 60 seconds for Spring Boot to finish starting up, then test:

```bash
# Test via Gateway
curl http://localhost:8084/companies

# Check Eureka
# Open http://localhost:8761 in your browser

# Check RabbitMQ Management Console
# Open http://localhost:15672 (guest/guest)

# Check Zipkin Tracing
# Open http://localhost:9411
```

#### Useful Docker Compose Commands

```bash
# View logs for a specific service
docker compose logs -f companyms

# Stop all services
docker compose down

# Stop and remove volumes (full reset)
docker compose down -v
```

---

### Method 3: Deploying on Kubernetes (Minikube)

This is the full production-like deployment. Images are pulled from Docker Hub. Kubernetes handles scaling, health checks, and service discovery.

> **Prerequisite:** You must have **Minikube** and **kubectl** installed. Docker Desktop must be running.

#### Step 1: Start Minikube

```bash
minikube start --memory=4096 --cpus=4
```

Enable the Metrics Server (required for HPA):
```bash
kubectl apply -f metrics-server.yaml
```

#### Step 2: Build and Push Images to Docker Hub

The images must be pushed to Docker Hub so Kubernetes can pull them. Replace `josamuel7` with your own Docker Hub username if you've forked the project.

```bash
# Company MS
cd companyms
./mvnw clean package -DskipTests
docker build -t josamuel7/companyms:latest .
docker push josamuel7/companyms:latest
cd ..

# Job MS
cd jobms
./mvnw clean package -DskipTests
docker build -t josamuel7/jobms:latest .
docker push josamuel7/jobms:latest
cd ..

# Review MS
cd reviewms
./mvnw clean package -DskipTests
docker build -t josamuel7/reviewms:latest .
docker push josamuel7/reviewms:latest
cd ..

# Gateway
cd gateway
./mvnw clean package -DskipTests
docker build -t josamuel7/gateway-ms:latest .
docker push josamuel7/gateway-ms:latest
cd ..
```

> **If you forked the project**, update the `image:` field in all deployment YAML files under `k8s/bootstrap/` and `k8s/services/` to match your Docker Hub username.

#### Step 3: Deploy Infrastructure Services

Apply all infrastructure manifests in order:

```bash
# PostgreSQL (with auto-init script for company/job/review databases)
kubectl apply -f k8s/services/postgres/

# RabbitMQ
kubectl apply -f k8s/services/rabbitmq/

# Redis
kubectl apply -f k8s/services/redis/

# Zipkin
kubectl apply -f k8s/services/zipkin/
```

Wait for all infrastructure pods to be `Running`:
```bash
kubectl get pods -w
```

Press `Ctrl+C` once `postgres-0`, `rabbitmq-...`, `redis-...`, and `zipkin-...` all show `1/1 Running`.

#### Step 4: Deploy Microservices and Gateway

```bash
kubectl apply -f k8s/bootstrap/company/
kubectl apply -f k8s/bootstrap/job/
kubectl apply -f k8s/bootstrap/review/
kubectl apply -f k8s/bootstrap/gateway/
```

#### Step 5: Wait for All Pods to be Ready

```bash
kubectl get pods -w
```

> **Important:** Spring Boot microservices take **3-5 minutes** to fully start on Minikube due to resource constraints. The `readinessProbe` on `/actuator/health` ensures that no traffic is routed to a pod until it is completely initialized. You will see `0/1 Running` for several minutes — this is normal. Do NOT proceed to testing until all pods show `1/1 Running`.

Once all pods show `1/1 Running`, press `Ctrl+C`.

#### Step 6: Forward the Gateway Port

Open a dedicated terminal and run the following (keep it running):

```bash
kubectl port-forward svc/gateway 8084:8084
```

#### Step 7: Verify

Open a **new terminal** and test:

```bash
# Windows (PowerShell)
curl.exe -s http://localhost:8084/companies

# macOS/Linux
curl -s http://localhost:8084/companies

# Create a company
curl.exe -X POST http://localhost:8084/companies \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"TechCorp\",\"description\":\"A great tech company\"}"
```

#### Useful Kubernetes Commands

```bash
# Check all resources
kubectl get all

# Check logs for a specific pod
kubectl logs -l app=company --tail=100

# Check pod details (useful for debugging)
kubectl describe pod -l app=gateway

# Check HPA status
kubectl get hpa

# Delete all deployed resources (full teardown)
kubectl delete -f k8s/bootstrap/
kubectl delete -f k8s/services/

# Stop Minikube
minikube stop
```

---

## Rate Limiting Verification

Once the system is running (via any method), you can verify the rate limiter using the `hey` HTTP load testing tool.

### Using Docker (no installation required)

```bash
# Send 50 concurrent requests to the Gateway
docker run --rm williamyeh/hey -n 50 -c 20 http://host.docker.internal:8084/companies
```

**Expected output:**
```
Status code distribution:
  [200] 20 responses   ← Allowed (burst capacity)
  [429] 30 responses   ← Rate limited
```

The Gateway response headers for rate-limited requests include:
```
X-RateLimit-Remaining: 0
X-RateLimit-Burst-Capacity: 20
X-RateLimit-Replenish-Rate: 10
```

### Configuration Reference

Rate limiter settings in `gateway/src/main/resources/application.properties`:
```properties
# Replenishes 10 tokens per second
spring.cloud.gateway.routes[0].filters[0].args.redis-rate-limiter.replenishRate=10
# Maximum burst size (tokens in bucket)
spring.cloud.gateway.routes[0].filters[0].args.redis-rate-limiter.burstCapacity=20
# Rate limit by client IP address
spring.cloud.gateway.routes[0].filters[0].args.key-resolver=#{@ipKeyResolver}
```

---

## Distributed Tracing with Zipkin

All microservices are instrumented with **Micrometer Tracing** (backed by Brave/Zipkin). Every request gets a `traceId` and `spanId` that are propagated across service boundaries via HTTP headers.

### Accessing Zipkin

| Mode | Zipkin URL |
|------|-----------|
| Local JARs | `http://localhost:9411` |
| Docker Compose | `http://localhost:9411` |
| Kubernetes | `kubectl port-forward svc/zipkin 9411:9411`, then `http://localhost:9411` |

### What You Can See

1. Open Zipkin at `http://localhost:9411`.
2. Click **"Run Query"**.
3. Click on any trace to see the complete distributed call tree.
4. For a `GET /jobs/1` request, you'll see: `gateway → job-service → company-service` and `gateway → job-service → review-service` as separate spans with timing.

---

## Project Structure

```
CareerMesh-Microservices-System/
│
├── companyms/                          # Company Microservice
│   ├── src/main/java/com/jos/companyms/
│   │   ├── CompanymsApplication.java
│   │   ├── company/
│   │   │   ├── Company.java            # JPA Entity
│   │   │   ├── CompanyController.java  # REST API
│   │   │   ├── CompanyService.java     # Service Interface
│   │   │   ├── CompanyRepository.java  # JPA Repository
│   │   │   ├── clients/
│   │   │   │   └── ReviewClient.java   # OpenFeign client → Review MS
│   │   │   ├── dto/
│   │   │   │   └── ReviewMessage.java  # RabbitMQ message DTO
│   │   │   ├── impl/
│   │   │   │   └── CompanyServiceImpl.java  # Business logic + caching
│   │   │   └── messaging/
│   │   │       ├── RabbitMQConfiguration.java   # Queue bean definitions
│   │   │       ├── CompanyMessageProducer.java  # Publishes company deleted event
│   │   │       ├── ReviewMessageConsumer.java   # Consumes review created event
│   │   │       └── JobDeletionFailedConsumer.java
│   │   └── config/
│   │       └── RedisCacheConfig.java   # Redisson cache manager setup
│   ├── src/main/resources/
│   │   ├── application.properties          # Local dev config
│   │   ├── application-docker.properties   # Docker Compose config
│   │   └── application-k8s.properties      # Kubernetes config
│   ├── Dockerfile
│   └── docker-compose.yaml             # Full system compose file
│
├── jobms/                              # Job Microservice
│   ├── src/main/java/com/jos/jobms/
│   │   ├── JobmsApplication.java
│   │   ├── config/
│   │   │   └── AppConfig.java          # RestTemplate + FeignClient config
│   │   └── job/
│   │       ├── Job.java                # JPA Entity (with DB indexes)
│   │       ├── JobController.java      # REST API (CRUD + search + filter)
│   │       ├── JobService.java
│   │       ├── JobRepository.java      # Includes full-text search query
│   │       ├── JobSpecification.java   # JPA Criteria API for dynamic filtering
│   │       ├── clients/
│   │       │   ├── CompanyClient.java  # OpenFeign → Company MS
│   │       │   └── ReviewClient.java   # OpenFeign → Review MS
│   │       ├── dto/
│   │       │   ├── JobDTO.java         # Aggregated response object
│   │       │   └── CompanyMessage.java
│   │       ├── external/
│   │       │   ├── Company.java        # External Company model
│   │       │   └── Review.java         # External Review model
│   │       ├── impl/
│   │       │   └── JobServiceImpl.java # Retry, cache, batch fetch logic
│   │       ├── mapper/
│   │       │   └── JobMapper.java      # Maps Job → JobDTO
│   │       └── messaging/
│   │           └── CompanyDeletedConsumer.java # Cascade delete jobs on company deletion
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   ├── application-docker.properties
│   │   └── application-k8s.properties
│   └── Dockerfile
│
├── reviewms/                           # Review Microservice
│   ├── src/main/java/com/jos/reviewms/
│   │   └── review/
│   │       ├── Review.java             # JPA Entity
│   │       ├── ReviewController.java   # REST API
│   │       ├── ReviewService.java
│   │       ├── ReviewRepository.java
│   │       ├── dto/
│   │       ├── impl/
│   │       │   └── ReviewServiceImpl.java
│   │       └── messaging/
│   │           ├── RabbitMQConfiguration.java
│   │           ├── ReviewMessageProducer.java   # Publishes review created event
│   │           └── CompanyDeletedConsumer.java  # Cascade deletes reviews
│   ├── src/main/resources/
│   └── Dockerfile
│
├── gateway/                            # API Gateway
│   ├── src/main/java/com/jos/gateway/
│   │   └── (GatewayApplication + IpKeyResolver bean)
│   ├── src/main/resources/
│   │   ├── application.properties          # Local: Eureka + localhost Redis
│   │   ├── application-docker.properties   # Docker: Eureka + redis container
│   │   └── application-k8s.properties      # K8s: No Eureka + redis K8s service
│   └── Dockerfile
│
├── service-reg/                        # Eureka Service Registry
│   └── (Spring Cloud Netflix Eureka Server)
│
├── configserver/                       # Spring Cloud Config Server (optional)
│
├── k8s/
│   ├── services/                       # Infrastructure K8s manifests
│   │   ├── postgres/
│   │   │   ├── configmap.yaml          # DB credentials + init.sql (auto-creates 3 DBs)
│   │   │   ├── statefulset.yaml
│   │   │   └── postgres-service.yaml
│   │   ├── rabbitmq/
│   │   ├── redis/
│   │   └── zipkin/
│   └── bootstrap/                      # Microservice K8s manifests
│       ├── company/
│       │   ├── company-deployment.yaml # imagePullPolicy: Always, readinessProbe
│       │   ├── company-service.yaml    # ClusterIP service on port 80
│       │   └── company-hpa.yaml        # HPA: 1-4 replicas, 70% CPU
│       ├── job/
│       ├── review/
│       └── gateway/
│           └── gateway-deployment.yaml # LoadBalancer service on port 8084
│
├── metrics-server.yaml                 # Kubernetes Metrics Server (for HPA)
└── README.md
```
