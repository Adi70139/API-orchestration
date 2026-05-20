# API Flow Engine — API Documentation

Base URL: `http://localhost:8060`

---

## Table of Contents
- [Modules](#modules)
- [Flows](#flows)
- [Flow Steps](#flow-steps)
- [Execution](#execution)

---

## Modules

Modules are top-level containers that group related flows together.

### Create a Module
```
POST /modules
```
**Request Body:**
```json
{
  "name": "Payment Module",
  "description": "Handles all payment-related flows"
}
```
**Response:** `201 Created`
```json
{
  "id": 1,
  "name": "Payment Module",
  "description": "Handles all payment-related flows"
}
```

---

### Get All Modules
```
GET /modules
```
**Response:** `200 OK`
```json
[
  {
    "id": 1,
    "name": "Payment Module",
    "description": "Handles all payment-related flows"
  }
]
```

---

### Get Module by ID
```
GET /modules/{moduleId}
```
**Response:** `200 OK`
```json
{
  "id": 1,
  "name": "Payment Module",
  "description": "Handles all payment-related flows"
}
```

---

### Update a Module
```
PUT /modules/{moduleId}
```
**Request Body:**
```json
{
  "name": "Payment Module Updated",
  "description": "Updated description"
}
```
**Response:** `200 OK`

---

### Delete a Module
```
DELETE /modules/{moduleId}
```
**Response:** `204 No Content`

---

## Flows

Flows belong to a module and contain an ordered list of steps.

### Create a Flow
```
POST /flows
```
**Request Body:**
```json
{
  "name": "Top Up Flow",
  "description": "Handles top-up payment",
  "module": "Payment Module"
}
```
> `module` is the **name** of the module, not the ID.

**Response:** `201 Created`
```json
{
  "id": 1,
  "name": "Top Up Flow",
  "description": "Handles top-up payment"
}
```

---

### Get All Flows
```
GET /flows
```
**Response:** `200 OK`

---

### Get Flow by ID
```
GET /flows/{flowId}
```
**Response:** `200 OK`

---

### Update a Flow
```
PUT /flows/{flowId}
```
**Request Body:**
```json
{
  "name": "Top Up Flow Updated",
  "description": "Updated description",
  "module": "Payment Module"
}
```
**Response:** `200 OK`

---

### Delete a Flow
```
DELETE /flows/{flowId}
```
**Response:** `204 No Content`

---

## Flow Steps

Steps belong to a flow and are executed in `stepOrder` sequence.

### Placeholder Support
Any field (`url`, `headersJson`, `bodyJson`) can contain `{placeholderName}` syntax.
Placeholders are resolved at runtime from **previous steps' response bodies**.

- Top-level field: `{id}` → resolves `id` from a previous response
- Nested field: `{user.id}` → resolves `user.id` using dot notation
- If a placeholder cannot be resolved → step fails with a descriptive error

The `requiredParams` field is **auto-extracted** on save — you never set it manually.

---

### Create a Flow Step
```
POST /flows/{flowId}/steps
```

**Minimal (GET, no headers/body):**
```json
{
  "name": "Get User",
  "stepOrder": 1,
  "method": "GET",
  "url": "https://jsonplaceholder.typicode.com/users/1"
}
```

**With headers and body (POST):**
```json
{
  "name": "Create Post",
  "stepOrder": 2,
  "method": "POST",
  "url": "https://jsonplaceholder.typicode.com/posts",
  "headersJson": "{\"Content-Type\": \"application/json\", \"Authorization\": \"Bearer token123\"}",
  "bodyJson": "{\"title\": \"Test Post\", \"body\": \"Hello World\", \"userId\": 1}"
}
```

**With dynamic placeholders (chained from previous step response):**
```json
{
  "name": "Get User Posts",
  "stepOrder": 2,
  "method": "GET",
  "url": "https://jsonplaceholder.typicode.com/posts?userId={id}"
}
```

```json
{
  "name": "Create Comment",
  "stepOrder": 3,
  "method": "POST",
  "url": "https://jsonplaceholder.typicode.com/comments",
  "headersJson": "{\"Authorization\": \"Bearer {token}\"}",
  "bodyJson": "{\"postId\": \"{id}\", \"name\": \"Test\", \"email\": \"test@test.com\", \"body\": \"Nice post\"}"
}
```

**Supported methods:** `GET`, `POST`, `PUT`, `PATCH`, `DELETE`

**Response:** `201 Created`
```json
{
  "id": 1,
  "name": "Get User",
  "stepOrder": 1,
  "method": "GET",
  "url": "https://jsonplaceholder.typicode.com/users/1",
  "headersJson": null,
  "bodyJson": null,
  "requiredParams": null
}
```

---

### Get All Steps for a Flow
```
GET /flows/{flowId}/steps
```
**Response:** `200 OK` — returns steps ordered by `stepOrder`

---

### Get a Single Step
```
GET /flows/{flowId}/steps/{stepId}
```
**Response:** `200 OK`

---

### Update a Step
```
PUT /flows/{flowId}/steps/{stepId}
```
**Request Body:** same as Create. `requiredParams` is re-extracted automatically.

**Response:** `200 OK`

---

### Duplicate a Step
```
POST /flows/{flowId}/steps/{stepId}/duplicate
```
**Request Body:** optional. If `name` is omitted, the new step is named `Copy of {originalName}`.
```json
{
  "name": "Copy of Get User"
}
```
The copied step is appended to the end of the same flow and keeps the original method, URL, headers, body, assertions, retry settings, and description.

**Response:** `201 Created`

---

### Delete a Step
```
DELETE /flows/{flowId}/steps/{stepId}
```
**Response:** `204 No Content`

---

## Execution

Runs flows and modules. Placeholder values are resolved from previous step responses — no input context required.

Results are **persisted**:
- `FlowExecution` — only the **latest** run per flow is kept (overwritten on each run)
- `StepExecution` — full detail for each step in the latest flow run
- `ModuleExecution` — **all** runs are kept (full history)

---

### Run a Flow
Executes all steps in the flow in `stepOrder` sequence. Stops on first failure.

```
POST /execute/flows/{flowId}
```
**Request Body:** none

**Response:** `200 OK`
```json
{
  "flowExecutionId": 3,
  "flowId": 1,
  "flowName": "Top Up Flow",
  "allStepsPassed": true,
  "totalDurationMs": 842,
  "stepResults": [
    {
      "stepId": 1,
      "stepName": "Get User",
      "stepOrder": 1,
      "resolvedUrl": "https://jsonplaceholder.typicode.com/users/1",
      "resolvedHeadersJson": null,
      "resolvedBodyJson": null,
      "statusCode": 200,
      "responseBody": "{\"id\": 1, \"name\": \"Leanne Graham\", ...}",
      "success": true,
      "errorMessage": null,
      "durationMs": 540
    },
    {
      "stepId": 2,
      "stepName": "Create Post",
      "stepOrder": 2,
      "resolvedUrl": "https://jsonplaceholder.typicode.com/posts",
      "resolvedHeadersJson": "{\"Content-Type\": \"application/json\"}",
      "resolvedBodyJson": "{\"title\": \"Test Post\", \"userId\": \"1\"}",
      "statusCode": 201,
      "responseBody": "{\"id\": 101, ...}",
      "success": true,
      "errorMessage": null,
      "durationMs": 302
    }
  ]
}
```

**On placeholder resolution failure:**
```json
{
  "flowExecutionId": 4,
  "flowId": 1,
  "flowName": "Top Up Flow",
  "allStepsPassed": false,
  "totalDurationMs": 541,
  "stepResults": [
    {
      "stepId": 2,
      "stepName": "Create Post",
      "stepOrder": 2,
      "success": false,
      "errorMessage": "Cannot resolve placeholder '{userId}'. Available keys from previous responses: [id, name, email]",
      "durationMs": 0
    }
  ]
}
```

---

### Run a Flow Asynchronously
Starts a flow in the background and returns a `flowExecutionId` immediately. Use this when the frontend needs live per-step progress.

```
POST /execute/flows/{flowId}/async
```

**Request Body:** optional
```json
{
  "environmentId": 1
}
```

**Response:** `200 OK`
```json
{
  "flowExecutionId": 3,
  "flowId": 1,
  "flowName": "Top Up Flow",
  "status": "IN_PROGRESS"
}
```

Poll live status:
```
GET /execute/flows/runs/{flowExecutionId}/status
```

**Response:** `200 OK`
```json
{
  "flowExecutionId": 3,
  "flowId": 1,
  "flowName": "Top Up Flow",
  "status": "IN_PROGRESS",
  "steps": [
    {
      "stepId": 1,
      "stepName": "Create Job",
      "stepOrder": 1,
      "status": "PASS",
      "statusCode": 202
    },
    {
      "stepId": 2,
      "stepName": "Poll Job",
      "stepOrder": 2,
      "status": "IN_PROGRESS",
      "statusCode": 404,
      "totalPollAttempts": 2
    },
    {
      "stepId": 3,
      "stepName": "Fetch Result",
      "stepOrder": 3,
      "status": "PENDING"
    }
  ]
}
```

Step statuses are `PENDING`, `IN_PROGRESS`, `PASS`, and `FAIL`. Polling steps stay `IN_PROGRESS` while their intermediate attempts return non-matching statuses, and only become `FAIL` when polling times out.

---

### Run a Module
Executes all flows in the module sequentially. Each flow runs all its steps in order.

```
POST /execute/modules/{moduleId}
```
**Request Body:** none

**Response:** `200 OK`
```json
{
  "moduleExecutionId": 1,
  "moduleId": 1,
  "moduleName": "Payment Module",
  "allFlowsPassed": true,
  "totalDurationMs": 1523,
  "flowResults": [
    {
      "flowExecutionId": 3,
      "flowId": 1,
      "flowName": "Top Up Flow",
      "allStepsPassed": true,
      "totalDurationMs": 842,
      "stepResults": [...]
    },
    {
      "flowExecutionId": 4,
      "flowId": 2,
      "flowName": "Refund Flow",
      "allStepsPassed": true,
      "totalDurationMs": 681,
      "stepResults": [...]
    }
  ]
}
```

---

## Error Responses

All errors return a consistent JSON shape:

| Status | Meaning |
|--------|---------|
| `400` | Bad request — validation failure or missing required field |
| `404` | Resource not found |
| `500` | Internal server error |

```json
{
  "error": "Flow not found with id: 99"
}
```

Validation errors return field-level detail:
```json
{
  "name": "must not be blank",
  "stepOrder": "must not be null",
  "url": "must not be blank"
}
```

---

## Placeholder Chaining — How It Works

When a step has `{placeholderName}` in its `url`, `headersJson`, or `bodyJson`:

1. The engine collects all **previous steps' response bodies** in order
2. Each response body is parsed as JSON and flattened into dot-notation keys
3. The placeholder is matched against those keys — **last step wins** on conflict
4. If unresolvable → step fails immediately with the available keys listed in the error

**Example chain:**

Step 1 response:
```json
{ "id": 1, "username": "Bret", "address": { "city": "Gwenborough" } }
```

Step 2 can use:
- `{id}` → `"1"`
- `{username}` → `"Bret"`
- `{address.city}` → `"Gwenborough"`

Step 2 body:
```json
{ "userId": "{id}", "location": "{address.city}" }
```

Resolved before sending:
```json
{ "userId": "1", "location": "Gwenborough" }
```
