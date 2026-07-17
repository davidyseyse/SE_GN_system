## 1. Project foundation

- [x] 1.1 Establish the frontend and backend module structure for teacher, student, API, and real-time communication layers
- [x] 1.2 Configure authentication, environment variables, Docker Compose, PostgreSQL, Redis, and WebSocket infrastructure
- [x] 1.3 Define shared enums and contracts for roles, question types, question states, buzz states, and leaderboard visibility

## 2. Data model and persistence

- [x] 2.1 Create database schemas and ORM mappings for users, classrooms, classroom members, questions, question options, buzz records, submissions, score records, leaderboard snapshots, and audit logs
- [x] 2.2 Add indexes and constraints for classroom codes, question ordering, submission uniqueness, and score lookup performance
- [x] 2.3 Implement repository and data access layers for classroom state, question state, buzz history, submission history, and score aggregation

## 3. Classroom and question management APIs

- [x] 3.1 Implement authentication and role-based authorization for teacher, student, and assistant actions
- [x] 3.2 Implement classroom creation, classroom join, membership lookup, and presence snapshot APIs
- [x] 3.3 Implement question CRUD, question ordering, publication settings, and classroom question list APIs
- [x] 3.4 Implement question state transition APIs with strict validation for legal state changes only

## 4. Real-time buzz and answer workflow

- [x] 4.1 Implement WebSocket session authentication, classroom subscription, and reconnect or resync flows
- [x] 4.2 Implement Redis-backed buzz locking and ordered winner selection with idempotent request handling
- [x] 4.3 Implement answer submission flow with answer-window checks, lock handling, and duplicate submission protection
- [x] 4.4 Implement auto-grading for objective questions and pending-review flow for short-answer questions
- [x] 4.5 Publish real-time events for presence, question opening, buzz opening, buzz results, submission receipts, statistics, and leaderboard changes

## 5. Scoring, leaderboard, and reporting

- [x] 5.1 Implement score calculation rules for correct answers, buzz bonuses, timeouts, and optional wrong-answer penalties
- [x] 5.2 Implement leaderboard calculation, ranking visibility rules, and teacher score breakdown views
- [x] 5.3 Implement classroom summary, question statistics, and buzz history query services
- [x] 5.4 Implement CSV or Excel export generation and audit logging for export actions

## 6. Teacher and student user interfaces

- [x] 6.1 Build teacher login, classroom list, classroom creation, and question bank management screens
- [x] 6.2 Build teacher live control console for question transitions, buzz control, live statistics, and leaderboard display
- [x] 6.3 Build student join, waiting room, buzz or answer interaction, and personal score screens
- [x] 6.4 Add responsive state handling, countdown timers, error feedback, and reconnect indicators for classroom clients

## 7. Validation and release readiness

- [ ] 7.1 Add automated tests for authorization, question state transitions, buzz winner uniqueness, and submission locking
- [ ] 7.2 Add integration or load tests covering 100 concurrent students, 50 concurrent buzz attempts, and reconnect recovery
- [ ] 7.3 Verify export correctness, audit log coverage, and leaderboard recalculation behavior
- [ ] 7.4 Prepare deployment documentation, operational checks, and rollback instructions for production release
