## MODIFIED Requirements

### Requirement: Teacher batch controls use concise labels

The teacher interface SHALL label the existing batch operations `開放全部搶答`, `停止全部搶答`, and `重置全部題目` without changing their command semantics.

#### Scenario: Teacher views batch controls

- **WHEN** an authorized teacher or assistant opens the classroom control interface
- **THEN** the three batch buttons display `開放全部搶答`, `停止全部搶答`, and `重置全部題目`

#### Scenario: Teacher invokes a renamed control

- **WHEN** the teacher activates any renamed batch button
- **THEN** the system invokes the same corresponding `OPEN_ALL`, `STOP_ALL`, or `RESET_ALL` operation, including existing confirmations, eligibility rules, and result reporting

